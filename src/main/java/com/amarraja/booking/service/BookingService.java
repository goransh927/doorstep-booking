package com.amarraja.booking.service;

import com.amarraja.booking.domain.Booking;
import com.amarraja.booking.domain.BookingStatus;
import com.amarraja.booking.domain.Occupancy;
import com.amarraja.booking.domain.SchedulingPolicy;
import com.amarraja.booking.domain.ServiceType;
import com.amarraja.booking.domain.Technician;
import com.amarraja.booking.persistence.BookingRepository;
import com.amarraja.booking.persistence.PostgresErrors;
import com.amarraja.booking.persistence.ReferenceDataRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class BookingService {

    private static final Logger log = LoggerFactory.getLogger(BookingService.class);

    private final ReferenceDataRepository referenceData;
    private final BookingRepository bookings;
    private final AvailabilityService availability;
    private final SchedulingPolicy policy;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public BookingService(ReferenceDataRepository referenceData, BookingRepository bookings,
                          AvailabilityService availability, SchedulingPolicy policy,
                          TransactionTemplate transactions, Clock clock) {
        this.referenceData = referenceData;
        this.bookings = bookings;
        this.availability = availability;
        this.policy = policy;
        this.transactions = transactions;
        this.clock = clock;
    }

    public record CreateBookingCommand(long serviceId, String pincode, Instant startTime, String customerPhone) {
    }

    /** {@code replayed} is true when an earlier request with the same Idempotency-Key made the booking. */
    public record BookingResult(Booking booking, boolean replayed) {
    }

    /**
     * Creates a booking and assigns a technician.
     *
     * <p>Concurrency: candidates are ranked from a lock-free snapshot, then tried one at a time.
     * Each attempt is its own short transaction that locks the technician row, re-checks for
     * overlap and inserts. Two requests for the same technician therefore serialise on that
     * lock, and the loser moves on to the next candidate or gets NO_AVAILABILITY. The
     * {@code booking_no_overlap} exclusion constraint backs this up at the database level.
     */
    public BookingResult create(CreateBookingCommand command, String idempotencyKey) {
        String fingerprint = fingerprint(command);
        if (idempotencyKey != null) {
            Optional<Booking> existing = bookings.findByIdempotencyKey(idempotencyKey);
            if (existing.isPresent()) {
                return replay(existing.get(), fingerprint);
            }
        }

        ServiceType service = referenceData.findService(command.serviceId())
                .orElseThrow(() -> ApiException.unknownService(command.serviceId()));
        long zoneId = referenceData.findZoneIdByPincode(command.pincode())
                .orElseThrow(() -> ApiException.unserviceablePincode(command.pincode()));

        Instant start = command.startTime();
        Instant end = start.plus(service.duration());
        Instant now = clock.instant();
        if (!policy.isAlignedToSlot(start)) {
            throw ApiException.slotNotAligned();
        }
        if (!policy.isWithinBookingWindow(start, end, now)) {
            throw ApiException.outsideBookingWindow();
        }

        LocalDate day = start.atZone(policy.zone()).toLocalDate();
        List<Technician> technicians =
                referenceData.findTechniciansInZoneWithSkill(zoneId, service.requiredSkill());
        Map<Long, List<Occupancy>> occupancies = availability.loadOccupancies(technicians, day);
        List<Technician> ranked = policy.rankFreeTechnicians(service, zoneId, start, technicians, occupancies);

        Booking draft = new Booking(0, command.customerPhone(), service.id(), command.pincode(), 0,
                start, end, policy.blockedUntil(end), BookingStatus.CONFIRMED, idempotencyKey,
                fingerprint, now, now, null);

        for (Technician technician : ranked) {
            try {
                Optional<Booking> booked = tryAssign(technician, draft);
                if (booked.isPresent()) {
                    log.info("Booked id={} technician={} start={}", booked.get().id(), technician.id(), start);
                    return new BookingResult(booked.get(), false);
                }
            } catch (DataIntegrityViolationException e) {
                if (PostgresErrors.isIdempotencyKeyViolation(e)) {
                    // A concurrent request with the same key won the race; hand back its booking.
                    Booking winner = bookings.findByIdempotencyKey(idempotencyKey).orElseThrow(() -> e);
                    return replay(winner, fingerprint);
                }
                if (!PostgresErrors.isOverlapViolation(e)) {
                    throw e;
                }
                log.warn("Exclusion constraint rejected technician={} start={}; trying next", technician.id(), start);
            }
        }
        log.info("No technician available service={} pincode={} start={}", service.id(), command.pincode(), start);
        throw ApiException.noAvailability();
    }

    private Optional<Booking> tryAssign(Technician technician, Booking draft) {
        return transactions.execute(tx -> {
            referenceData.lockTechnician(technician.id());
            if (bookings.hasOverlap(technician.id(), draft.startTime(), draft.blockedUntil())) {
                return Optional.<Booking>empty();
            }
            return Optional.of(bookings.insert(withTechnician(draft, technician.id())));
        });
    }

    @Transactional(readOnly = true)
    public Booking get(long id) {
        return bookings.findById(id).orElseThrow(() -> ApiException.notFound("Booking", id));
    }

    /** Cancelling an already-cancelled booking is a no-op that returns it, so clients can retry safely. */
    @Transactional
    public Booking cancel(long id) {
        Booking booking = bookings.findByIdForUpdate(id)
                .orElseThrow(() -> ApiException.notFound("Booking", id));
        switch (booking.status()) {
            case CANCELLED -> {
                return booking;
            }
            case COMPLETED -> throw ApiException.notCancellable(booking.status().name());
            case CONFIRMED -> {
                Instant now = clock.instant();
                if (!policy.canCancel(booking.startTime(), now)) {
                    throw ApiException.cancellationWindowClosed();
                }
                bookings.markCancelled(id, now);
                log.info("Cancelled booking id={}", id);
                return bookings.findById(id).orElseThrow();
            }
            default -> throw new IllegalStateException("Unhandled status " + booking.status());
        }
    }

    @Transactional(readOnly = true)
    public Page<Booking> findByPhone(String phone, BookingStatus status, int page, int size) {
        long total = bookings.countByPhone(phone, status);
        List<Booking> items = bookings.findByPhone(phone, status, size, (long) page * size);
        return new Page<>(items, page, size, total);
    }

    private BookingResult replay(Booking existing, String fingerprint) {
        if (!fingerprint.equals(existing.requestFingerprint())) {
            throw ApiException.idempotencyKeyReused();
        }
        return new BookingResult(existing, true);
    }

    private static Booking withTechnician(Booking b, long technicianId) {
        return new Booking(b.id(), b.customerPhone(), b.serviceId(), b.pincode(), technicianId,
                b.startTime(), b.endTime(), b.blockedUntil(), b.status(), b.idempotencyKey(),
                b.requestFingerprint(), b.createdAt(), b.updatedAt(), b.cancelledAt());
    }

    static String fingerprint(CreateBookingCommand c) {
        String canonical = c.serviceId() + "|" + c.pincode() + "|" + c.startTime() + "|" + c.customerPhone();
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
