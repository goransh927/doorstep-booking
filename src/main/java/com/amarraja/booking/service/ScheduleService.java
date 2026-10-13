package com.amarraja.booking.service;

import com.amarraja.booking.domain.Booking;
import com.amarraja.booking.domain.SchedulingPolicy;
import com.amarraja.booking.domain.Technician;
import com.amarraja.booking.persistence.BookingRepository;
import com.amarraja.booking.persistence.ReferenceDataRepository;
import java.time.LocalDate;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ScheduleService {

    private final ReferenceDataRepository referenceData;
    private final BookingRepository bookings;
    private final SchedulingPolicy policy;

    public ScheduleService(ReferenceDataRepository referenceData, BookingRepository bookings,
                           SchedulingPolicy policy) {
        this.referenceData = referenceData;
        this.bookings = bookings;
        this.policy = policy;
    }

    public record DaySchedule(Technician technician, LocalDate date, List<Booking> bookings) {
    }

    /** The technician's live (non-cancelled) bookings starting on {@code date}, earliest first. */
    @Transactional(readOnly = true)
    public DaySchedule schedule(long technicianId, LocalDate date) {
        Technician technician = referenceData.findTechnician(technicianId)
                .orElseThrow(() -> ApiException.notFound("Technician", technicianId));
        List<Booking> day = bookings.findLiveForTechnician(technicianId,
                policy.startOfDay(date), policy.startOfDay(date.plusDays(1)));
        return new DaySchedule(technician, date, day);
    }
}
