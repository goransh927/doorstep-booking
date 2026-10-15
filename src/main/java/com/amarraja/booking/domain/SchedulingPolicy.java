package com.amarraja.booking.domain;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

public class SchedulingPolicy {

    private final ZoneId zone;
    private final Duration slotGranularity;
    private final Duration travelBuffer;
    private final Duration minLeadTime;
    private final Duration maxHorizon;
    private final Duration cancellationCutoff;

    public SchedulingPolicy(ZoneId zone, Duration slotGranularity, Duration travelBuffer,
                            Duration minLeadTime, Duration maxHorizon, Duration cancellationCutoff) {
        if (slotGranularity.isZero() || slotGranularity.isNegative()) {
            throw new IllegalArgumentException("slotGranularity must be positive");
        }
        this.zone = zone;
        this.slotGranularity = slotGranularity;
        this.travelBuffer = travelBuffer;
        this.minLeadTime = minLeadTime;
        this.maxHorizon = maxHorizon;
        this.cancellationCutoff = cancellationCutoff;
    }

    public ZoneId zone() {
        return zone;
    }

    /** Slots start on granularity boundaries of local time, e.g. :00 and :30, with no seconds. */
    public boolean isAlignedToSlot(Instant start) {
        LocalTime local = start.atZone(zone).toLocalTime();
        long secondsOfDay = local.toSecondOfDay();
        return local.getNano() == 0 && secondsOfDay % slotGranularity.toSeconds() == 0;
    }

    /** The whole booking must sit between now + min lead time and now + max horizon. */
    public boolean isWithinBookingWindow(Instant start, Instant end, Instant now) {
        return !start.isBefore(now.plus(minLeadTime)) && !end.isAfter(now.plus(maxHorizon));
    }

    public boolean canCancel(Instant bookingStart, Instant now) {
        return !now.isAfter(bookingStart.minus(cancellationCutoff));
    }

    public Instant blockedUntil(Instant end) {
        return end.plus(travelBuffer);
    }

    public boolean isQualified(Technician technician, ServiceType service, long zoneId) {
        return technician.servesZone(zoneId) && technician.canPerform(service);
    }

    /**
     * Whether the technician could take a job over [start, end): it fits the shift and,
     * including the travel buffer on both this job and the existing ones, overlaps nothing.
     */
    public boolean isFree(Technician technician, Instant start, Instant end,
                          Collection<Occupancy> technicianOccupancies) {
        LocalDateTime localStart = LocalDateTime.ofInstant(start, zone);
        LocalDateTime localEnd = LocalDateTime.ofInstant(end, zone);
        if (!technician.shiftCovers(localStart, localEnd)) {
            return false;
        }
        Instant blockedUntil = blockedUntil(end);
        return technicianOccupancies.stream().noneMatch(o -> o.overlaps(start, blockedUntil));
    }

    /**
     * Technicians free for the job, best first: fewest bookings that day, ties broken by the
     * lowest technician id so assignment is deterministic and easy to reason about in tests.
     *
     * @param occupancies live bookings per technician id overlapping the job's day
     */
    public List<Technician> rankFreeTechnicians(ServiceType service, long zoneId, Instant start,
                                                Collection<Technician> technicians,
                                                Map<Long, List<Occupancy>> occupancies) {
        Instant end = start.plus(service.duration());
        LocalDate day = LocalDateTime.ofInstant(start, zone).toLocalDate();
        return technicians.stream()
                .filter(t -> isQualified(t, service, zoneId))
                .filter(t -> isFree(t, start, end, occupancies.getOrDefault(t.id(), List.of())))
                .sorted(Comparator
                        .comparingLong((Technician t) -> bookingsOnDay(occupancies.get(t.id()), day))
                        .thenComparingLong(Technician::id))
                .toList();
    }

    /**
     * Every aligned start time on {@code day} at which at least one technician's shift could
     * hold the whole job. Says nothing about existing bookings or the booking window.
     */
    public List<Instant> candidateStarts(LocalDate day, ServiceType service,
                                         Collection<Technician> technicians) {
        if (technicians.isEmpty()) {
            return List.of();
        }
        LocalTime earliest = technicians.stream().map(Technician::shiftStart)
                .min(Comparator.naturalOrder()).orElseThrow();
        LocalTime latest = technicians.stream().map(Technician::shiftEnd)
                .max(Comparator.naturalOrder()).orElseThrow();

        List<Instant> starts = new ArrayList<>();
        LocalDateTime cursor = alignUp(day.atTime(earliest));
        LocalDateTime lastEnd = day.atTime(latest);
        while (!cursor.plus(service.duration()).isAfter(lastEnd)
                && cursor.toLocalDate().equals(day)) {
            starts.add(cursor.atZone(zone).toInstant());
            cursor = cursor.plus(slotGranularity);
        }
        return starts;
    }

    public Instant startOfDay(LocalDate day) {
        return day.atStartOfDay(zone).toInstant();
    }

    private long bookingsOnDay(List<Occupancy> occupancies, LocalDate day) {
        if (occupancies == null) {
            return 0;
        }
        return occupancies.stream()
                .filter(o -> LocalDateTime.ofInstant(o.start(), zone).toLocalDate().equals(day))
                .count();
    }

    private LocalDateTime alignUp(LocalDateTime time) {
        long step = slotGranularity.toSeconds();
        long seconds = time.toLocalTime().toSecondOfDay();
        long remainder = seconds % step;
        LocalDateTime truncated = time.withNano(0);
        return remainder == 0 ? truncated : truncated.plusSeconds(step - remainder);
    }
}
