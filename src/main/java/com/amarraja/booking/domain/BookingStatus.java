package com.amarraja.booking.domain;

public enum BookingStatus {
    CONFIRMED,
    CANCELLED,
    COMPLETED;

    /** Whether a booking in this status still holds the technician's time. */
    public boolean occupiesTechnician() {
        return this != CANCELLED;
    }
}
