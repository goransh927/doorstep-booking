package com.amarraja.booking.web.dto;

import com.amarraja.booking.web.support.IstDateTimeDeserializer;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import java.time.OffsetDateTime;

public record CreateBookingRequest(
        @NotNull @Positive
        @Schema(example = "1")
        Long serviceId,

        @NotBlank @Pattern(regexp = Patterns.PINCODE, message = "must be a 6-digit pincode")
        @Schema(example = "560034")
        String pincode,

        @NotNull
        @JsonDeserialize(using = IstDateTimeDeserializer.class)
        @Schema(example = "2026-10-15T10:00:00+05:30",
                description = "ISO-8601. Without an offset the time is read as IST.")
        OffsetDateTime startTime,

        @NotBlank @Pattern(regexp = Patterns.PHONE, message = "must be 10-15 digits, optionally starting with +")
        @Schema(example = "+919876543210")
        String customerPhone) {
}
