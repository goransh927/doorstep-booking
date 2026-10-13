package com.amarraja.booking.domain;

import java.time.Instant;

/**
 * The time a technician is unavailable because of one booking: from its start until its end
 * plus the travel buffer, as a half-open interval [start, blockedUntil).
 */
public record Occupancy(long technicianId, Instant start, Instant blockedUntil) {

    public boolean overlaps(Instant otherStart, Instant otherBlockedUntil) {
        return start.isBefore(otherBlockedUntil) && otherStart.isBefore(blockedUntil);
    }
}
