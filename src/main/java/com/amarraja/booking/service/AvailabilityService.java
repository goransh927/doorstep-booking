package com.amarraja.booking.service;

import com.amarraja.booking.domain.Occupancy;
import com.amarraja.booking.domain.SchedulingPolicy;
import com.amarraja.booking.domain.ServiceType;
import com.amarraja.booking.domain.Technician;
import com.amarraja.booking.persistence.BookingRepository;
import com.amarraja.booking.persistence.ReferenceDataRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

@Service
public class AvailabilityService {

    private final ReferenceDataRepository referenceData;
    private final BookingRepository bookings;
    private final SchedulingPolicy policy;
    private final Clock clock;

    public AvailabilityService(ReferenceDataRepository referenceData, BookingRepository bookings,
                               SchedulingPolicy policy, Clock clock) {
        this.referenceData = referenceData;
        this.bookings = bookings;
        this.policy = policy;
        this.clock = clock;
    }

    public record Slot(Instant start, Instant end, int freeTechnicians) {
    }

    public record DayAvailability(ServiceType service, String pincode, LocalDate date, List<Slot> slots) {
    }

    /**
     * Bookable starts for one day. This is a snapshot: a listed slot can still be taken by
     * someone else before the customer books it, in which case booking returns NO_AVAILABILITY.
     */
    public DayAvailability availability(long serviceId, String pincode, LocalDate date) {
        ServiceType service = referenceData.findService(serviceId)
                .orElseThrow(() -> ApiException.unknownService(serviceId));
        long zoneId = referenceData.findZoneIdByPincode(pincode)
                .orElseThrow(() -> ApiException.unserviceablePincode(pincode));

        List<Technician> technicians =
                referenceData.findTechniciansInZoneWithSkill(zoneId, service.requiredSkill());
        Map<Long, List<Occupancy>> occupancies = loadOccupancies(technicians, date);

        Instant now = clock.instant();
        List<Slot> slots = policy.candidateStarts(date, service, technicians).stream()
                .filter(start -> policy.isWithinBookingWindow(start, start.plus(service.duration()), now))
                .map(start -> new Slot(start, start.plus(service.duration()),
                        policy.rankFreeTechnicians(service, zoneId, start, technicians, occupancies).size()))
                .filter(slot -> slot.freeTechnicians() > 0)
                .toList();
        return new DayAvailability(service, pincode, date, slots);
    }

    /**
     * Live bookings of these technicians that touch the given day, grouped by technician.
     * Shared with booking creation so both paths see the same picture.
     */
    Map<Long, List<Occupancy>> loadOccupancies(List<Technician> technicians, LocalDate date) {
        List<Long> ids = technicians.stream().map(Technician::id).toList();
        return bookings.findOccupancies(ids, policy.startOfDay(date), policy.startOfDay(date.plusDays(1)))
                .stream()
                .collect(Collectors.groupingBy(Occupancy::technicianId));
    }
}
