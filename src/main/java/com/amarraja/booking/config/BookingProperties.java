package com.amarraja.booking.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.Duration;
import java.time.ZoneId;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "booking")
public record BookingProperties(
        @NotNull ZoneId timeZone,
        @NotNull Duration slotGranularity,
        @NotNull Duration travelBuffer,
        @NotNull Duration minLeadTime,
        @NotNull Duration maxHorizon,
        @NotNull Duration cancellationCutoff,
        @Valid @NotNull RateLimit rateLimit) {

    public record RateLimit(boolean enabled, @Positive int capacity, @Positive int refillPerMinute) {
    }
}
