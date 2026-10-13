package com.amarraja.booking.web;

import com.amarraja.booking.domain.Booking;
import com.amarraja.booking.domain.BookingStatus;
import com.amarraja.booking.service.BookingService;
import com.amarraja.booking.service.BookingService.BookingResult;
import com.amarraja.booking.service.BookingService.CreateBookingCommand;
import com.amarraja.booking.service.Page;
import com.amarraja.booking.web.dto.CreateBookingRequest;
import com.amarraja.booking.web.dto.Patterns;
import com.amarraja.booking.web.dto.Responses.BookingResponse;
import com.amarraja.booking.web.dto.Responses.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.net.URI;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/bookings")
public class BookingController {

    public static final String IDEMPOTENCY_KEY = "Idempotency-Key";
    public static final String IDEMPOTENT_REPLAYED = "Idempotent-Replayed";

    private final BookingService bookingService;
    private final BookingResponseMapper mapper;

    public BookingController(BookingService bookingService, BookingResponseMapper mapper) {
        this.bookingService = bookingService;
        this.mapper = mapper;
    }

    @Operation(summary = "Create a booking and assign a technician",
            description = "Retries with the same Idempotency-Key return the original booking "
                    + "(same 201, plus Idempotent-Replayed: true).")
    @PostMapping
    public ResponseEntity<BookingResponse> create(
            @RequestHeader(name = IDEMPOTENCY_KEY, required = false)
            @Size(min = 1, max = 255) @Parameter(description = "Client-generated unique key, e.g. a UUID")
            String idempotencyKey,
            @Valid @RequestBody CreateBookingRequest request) {
        CreateBookingCommand command = new CreateBookingCommand(request.serviceId(), request.pincode(),
                request.startTime().toInstant(), request.customerPhone());
        BookingResult result = bookingService.create(command, idempotencyKey);
        return ResponseEntity.created(URI.create("/bookings/" + result.booking().id()))
                .header(IDEMPOTENT_REPLAYED, String.valueOf(result.replayed()))
                .body(mapper.toResponse(result.booking()));
    }

    @Operation(summary = "Get one booking")
    @GetMapping("/{id}")
    public BookingResponse get(@PathVariable long id) {
        return mapper.toResponse(bookingService.get(id));
    }

    @Operation(summary = "Cancel a booking (up to 1 hour before start)")
    @PostMapping("/{id}/cancel")
    public BookingResponse cancel(@PathVariable long id) {
        return mapper.toResponse(bookingService.cancel(id));
    }

    @Operation(summary = "List a customer's bookings, newest start first")
    @GetMapping
    public PageResponse<BookingResponse> list(
            @RequestParam @Pattern(regexp = Patterns.PHONE) String phone,
            @RequestParam(required = false) BookingStatus status,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        Page<Booking> result = bookingService.findByPhone(phone, status, page, size);
        return new PageResponse<>(mapper.toResponses(result.items()), result.page(), result.size(),
                result.totalItems(), result.totalPages());
    }
}
