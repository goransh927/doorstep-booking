package com.amarraja.booking.domain;

import java.time.Instant;

public record Booking(
        long id,
        String customerPhone,
        long serviceId,
        String pincode,
        long technicianId,
        Instant startTime,
        Instant endTime,
        Instant blockedUntil,
        BookingStatus status,
        String idempotencyKey,
        String requestFingerprint,
        Instant createdAt,
        Instant updatedAt,
        Instant cancelledAt) {
}
