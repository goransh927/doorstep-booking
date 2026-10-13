package com.amarraja.booking.web;

import com.amarraja.booking.domain.Booking;
import com.amarraja.booking.domain.SchedulingPolicy;
import com.amarraja.booking.domain.ServiceType;
import com.amarraja.booking.domain.Technician;
import com.amarraja.booking.persistence.ReferenceDataRepository;
import com.amarraja.booking.web.dto.Responses.BookingResponse;
import com.amarraja.booking.web.dto.Responses.ServiceSummary;
import com.amarraja.booking.web.dto.Responses.TechnicianSummary;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class BookingResponseMapper {

    private final ReferenceDataRepository referenceData;
    private final SchedulingPolicy policy;

    public BookingResponseMapper(ReferenceDataRepository referenceData, SchedulingPolicy policy) {
        this.referenceData = referenceData;
        this.policy = policy;
    }

    public BookingResponse toResponse(Booking booking) {
        return toResponses(List.of(booking)).get(0);
    }

    /** Looks each referenced service and technician up once per call, not once per booking. */
    public List<BookingResponse> toResponses(List<Booking> bookings) {
        Map<Long, ServiceSummary> services = new HashMap<>();
        Map<Long, TechnicianSummary> technicians = new HashMap<>();
        return bookings.stream().map(b -> new BookingResponse(
                b.id(),
                b.status(),
                b.customerPhone(),
                services.computeIfAbsent(b.serviceId(), this::service),
                b.pincode(),
                local(b.startTime()),
                local(b.endTime()),
                technicians.computeIfAbsent(b.technicianId(), this::technician),
                local(b.createdAt()),
                local(b.cancelledAt()))).toList();
    }

    public static ServiceSummary summary(ServiceType s) {
        return new ServiceSummary(s.id(), s.name(), s.durationMinutes());
    }

    public static TechnicianSummary summary(Technician t) {
        return new TechnicianSummary(t.id(), t.name());
    }

    public OffsetDateTime local(Instant instant) {
        return instant == null ? null : instant.atZone(policy.zone()).toOffsetDateTime();
    }

    private ServiceSummary service(long id) {
        return referenceData.findService(id).map(BookingResponseMapper::summary).orElseThrow();
    }

    private TechnicianSummary technician(long id) {
        return referenceData.findTechnician(id).map(BookingResponseMapper::summary).orElseThrow();
    }
}
