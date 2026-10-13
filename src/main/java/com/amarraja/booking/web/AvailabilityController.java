package com.amarraja.booking.web;

import com.amarraja.booking.service.AvailabilityService;
import com.amarraja.booking.service.AvailabilityService.DayAvailability;
import com.amarraja.booking.web.dto.Patterns;
import com.amarraja.booking.web.dto.Responses.AvailabilityResponse;
import com.amarraja.booking.web.dto.Responses.SlotResponse;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
public class AvailabilityController {

    private final AvailabilityService availabilityService;
    private final BookingResponseMapper mapper;

    public AvailabilityController(AvailabilityService availabilityService, BookingResponseMapper mapper) {
        this.availabilityService = availabilityService;
        this.mapper = mapper;
    }

    @Operation(summary = "Bookable start times for a service at a pincode on a day (IST)")
    @GetMapping("/availability")
    public AvailabilityResponse availability(
            @RequestParam @Positive long serviceId,
            @RequestParam @Pattern(regexp = Patterns.PINCODE) String pincode,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        DayAvailability day = availabilityService.availability(serviceId, pincode, date);
        return new AvailabilityResponse(
                BookingResponseMapper.summary(day.service()),
                day.pincode(),
                day.date(),
                day.slots().stream()
                        .map(s -> new SlotResponse(mapper.local(s.start()), mapper.local(s.end()), s.freeTechnicians()))
                        .toList());
    }
}
