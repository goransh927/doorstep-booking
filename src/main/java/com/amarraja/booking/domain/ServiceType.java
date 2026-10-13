package com.amarraja.booking.domain;

import java.time.Duration;

public record ServiceType(long id, String name, int durationMinutes, Skill requiredSkill) {

    public Duration duration() {
        return Duration.ofMinutes(durationMinutes);
    }
}
