package com.amarraja.booking.web.dto;

import com.amarraja.booking.domain.BookingStatus;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

public final class Responses {

    private Responses() {
    }

    public record ServiceSummary(long id, String name, int durationMinutes) {
    }

    public record TechnicianSummary(long id, String name) {
    }

    public record BookingResponse(
            long id,
            BookingStatus status,
            String customerPhone,
            ServiceSummary service,
            String pincode,
            OffsetDateTime startTime,
            OffsetDateTime endTime,
            TechnicianSummary technician,
            OffsetDateTime createdAt,
            OffsetDateTime cancelledAt) {
    }

    public record SlotResponse(OffsetDateTime startTime, OffsetDateTime endTime, int availableTechnicians) {
    }

    public record AvailabilityResponse(ServiceSummary service, String pincode, LocalDate date,
                                       List<SlotResponse> slots) {
    }

    public record ScheduleResponse(TechnicianSummary technician, LocalDate date, List<BookingResponse> bookings) {
    }

    public record PageResponse<T>(List<T> items, int page, int size, long totalItems, int totalPages) {
    }
}
