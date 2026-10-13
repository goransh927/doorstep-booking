package com.amarraja.booking.service;

import org.springframework.http.HttpStatus;

/**
 * A failure the client can act on. Each carries a stable machine-readable {@code code}
 * alongside the HTTP status; the message is for humans.
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public ApiException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

    public static ApiException notFound(String what, Object id) {
        return new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", what + " " + id + " not found");
    }

    public static ApiException unknownService(long serviceId) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "UNKNOWN_SERVICE",
                "Service " + serviceId + " does not exist");
    }

    public static ApiException unserviceablePincode(String pincode) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "UNSERVICEABLE_PINCODE",
                "Pincode " + pincode + " is not covered by any zone");
    }

    public static ApiException slotNotAligned() {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "SLOT_NOT_ALIGNED",
                "Start time must be on a 30-minute boundary (e.g. 10:00 or 10:30 IST)");
    }

    public static ApiException outsideBookingWindow() {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "OUTSIDE_BOOKING_WINDOW",
                "Bookings must start at least 2 hours from now and end within 7 days from now");
    }

    public static ApiException noAvailability() {
        return new ApiException(HttpStatus.CONFLICT, "NO_AVAILABILITY",
                "No qualified technician is free for this slot");
    }

    public static ApiException cancellationWindowClosed() {
        return new ApiException(HttpStatus.CONFLICT, "CANCELLATION_WINDOW_CLOSED",
                "Bookings can only be cancelled up to 1 hour before the start time");
    }

    public static ApiException notCancellable(String status) {
        return new ApiException(HttpStatus.CONFLICT, "BOOKING_NOT_CANCELLABLE",
                "A " + status + " booking cannot be cancelled");
    }

    public static ApiException idempotencyKeyReused() {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "IDEMPOTENCY_KEY_REUSED",
                "This Idempotency-Key was already used with a different request body");
    }
}
