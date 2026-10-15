package com.amarraja.booking.domain;

import java.time.Instant;

public record Occupancy(long technicianId, Instant start, Instant blockedUntil) {

    public boolean overlaps(Instant otherStart, Instant otherBlockedUntil) {
        return start.isBefore(otherBlockedUntil) && otherStart.isBefore(blockedUntil);
    }
}
