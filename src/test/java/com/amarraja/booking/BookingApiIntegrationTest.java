package com.amarraja.booking;

import static org.assertj.core.api.Assertions.assertThat;

import com.amarraja.booking.support.IntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

class BookingApiIntegrationTest extends IntegrationTest {

    private static final String PHONE = "+919876543210";

    private List<String> availableStarts(long serviceId, String pincode) {
        ResponseEntity<JsonNode> response =
                get("/availability?serviceId=" + serviceId + "&pincode=" + pincode + "&date=" + TOMORROW);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<String> starts = new ArrayList<>();
        response.getBody().get("slots").forEach(slot -> starts.add(slot.get("startTime").asText()));
        return starts;
    }

    @Test
    void bookViewScheduleCancelLifecycle() {
        assertThat(availableStarts(JUMPSTART, ZONE_EAST_PINCODE))
                .hasSize(24)
                .contains(tomorrowAt("10:00"));

        ResponseEntity<JsonNode> created = book(JUMPSTART, ZONE_EAST_PINCODE, tomorrowAt("10:00"), PHONE);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode booking = created.getBody();
        long id = booking.get("id").asLong();
        assertThat(created.getHeaders().getLocation()).hasToString("/bookings/" + id);
        assertThat(booking.get("status").asText()).isEqualTo("CONFIRMED");
        assertThat(booking.get("technician").get("id").asLong()).isEqualTo(KIRAN);
        assertThat(booking.get("startTime").asText()).isEqualTo(tomorrowAt("10:00"));
        assertThat(booking.get("endTime").asText()).isEqualTo(tomorrowAt("10:30"));
        assertThat(booking.get("service").get("name").asText()).isEqualTo("Jump-start");

        assertThat(get("/bookings/" + id).getBody()).isEqualTo(booking);

        // 10:00-10:30 plus the travel buffer blocks 09:30, 10:00 and 10:30; 11:00 is free again.
        assertThat(availableStarts(JUMPSTART, ZONE_EAST_PINCODE))
                .doesNotContain(tomorrowAt("09:30"), tomorrowAt("10:00"), tomorrowAt("10:30"))
                .contains(tomorrowAt("09:00"), tomorrowAt("11:00"));

        JsonNode schedule = get("/technicians/" + KIRAN + "/schedule?date=" + TOMORROW).getBody();
        assertThat(schedule.get("bookings")).hasSize(1);
        assertThat(schedule.get("bookings").get(0).get("id").asLong()).isEqualTo(id);

        ResponseEntity<JsonNode> cancelled = post("/bookings/" + id + "/cancel");
        assertThat(cancelled.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(cancelled.getBody().get("status").asText()).isEqualTo("CANCELLED");
        assertThat(cancelled.getBody().get("cancelledAt")).isNotNull();

        // Cancelled time is bookable again immediately.
        assertThat(availableStarts(JUMPSTART, ZONE_EAST_PINCODE)).contains(tomorrowAt("10:00"));
        assertThat(get("/technicians/" + KIRAN + "/schedule?date=" + TOMORROW).getBody().get("bookings")).isEmpty();
        assertThat(book(JUMPSTART, ZONE_EAST_PINCODE, tomorrowAt("10:00"), PHONE).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void cancellingTwiceIsANoOp() {
        long id = book(JUMPSTART, ZONE_EAST_PINCODE, tomorrowAt("10:00"), PHONE).getBody().get("id").asLong();
        post("/bookings/" + id + "/cancel");
        ResponseEntity<JsonNode> again = post("/bookings/" + id + "/cancel");
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(again.getBody().get("status").asText()).isEqualTo("CANCELLED");
    }

    @Test
    void assignsTechnicianWithFewestBookingsThatDay() {
        // Zone South battery fitters: Ravi (1) and Anil (3). Ties go to the lower id.
        assertThat(technicianFor(book(BATTERY_REPLACEMENT, ZONE_SOUTH_PINCODE, tomorrowAt("10:00"), PHONE))).isEqualTo(1);
        assertThat(technicianFor(book(BATTERY_REPLACEMENT, ZONE_SOUTH_PINCODE, tomorrowAt("14:00"), PHONE))).isEqualTo(3);
        assertThat(technicianFor(book(BATTERY_REPLACEMENT, ZONE_SOUTH_PINCODE, tomorrowAt("16:00"), PHONE))).isEqualTo(1);
    }

    @Test
    void schedulesAreReturnedInStartOrder() {
        book(JUMPSTART, ZONE_EAST_PINCODE, tomorrowAt("15:00"), PHONE);
        book(JUMPSTART, ZONE_EAST_PINCODE, tomorrowAt("09:00"), PHONE);
        book(JUMPSTART, ZONE_EAST_PINCODE, tomorrowAt("12:00"), PHONE);

        JsonNode bookings = get("/technicians/" + KIRAN + "/schedule?date=" + TOMORROW).getBody().get("bookings");
        assertThat(bookings).extracting(b -> b.get("startTime").asText())
                .containsExactly(tomorrowAt("09:00"), tomorrowAt("12:00"), tomorrowAt("15:00"));
    }

    @Test
    void acceptsLocalTimeWithoutOffsetAsIst() {
        ResponseEntity<JsonNode> created = book(JUMPSTART, ZONE_EAST_PINCODE, TOMORROW + "T10:00", PHONE);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getBody().get("startTime").asText()).isEqualTo(tomorrowAt("10:00"));
    }

    @Nested
    class Idempotency {

        @Test
        void retryWithSameKeyReturnsOriginalBooking() {
            ResponseEntity<JsonNode> first = book(JUMPSTART, ZONE_EAST_PINCODE, tomorrowAt("10:00"), PHONE, "key-1");
            ResponseEntity<JsonNode> retry = book(JUMPSTART, ZONE_EAST_PINCODE, tomorrowAt("10:00"), PHONE, "key-1");

            assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            assertThat(first.getHeaders().getFirst("Idempotent-Replayed")).isEqualTo("false");
            assertThat(retry.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            assertThat(retry.getHeaders().getFirst("Idempotent-Replayed")).isEqualTo("true");
            assertThat(retry.getBody()).isEqualTo(first.getBody());
            assertThat(bookingCount()).isEqualTo(1);
        }

        @Test
        void sameKeyWithDifferentBodyIsRejected() {
            book(JUMPSTART, ZONE_EAST_PINCODE, tomorrowAt("10:00"), PHONE, "key-1");
            ResponseEntity<JsonNode> reused = book(JUMPSTART, ZONE_EAST_PINCODE, tomorrowAt("12:00"), PHONE, "key-1");

            assertError(reused, HttpStatus.UNPROCESSABLE_ENTITY, "IDEMPOTENCY_KEY_REUSED");
            assertThat(bookingCount()).isEqualTo(1);
        }

        @Test
        void requestsWithoutKeyAreIndependent() {
            book(JUMPSTART, ZONE_EAST_PINCODE, tomorrowAt("10:00"), PHONE);
            book(JUMPSTART, ZONE_EAST_PINCODE, tomorrowAt("12:00"), PHONE);
            assertThat(bookingCount()).isEqualTo(2);
        }
    }

    @Nested
    class Errors {

        @Test
        void unknownBookingIs404() {
            ResponseEntity<JsonNode> response = get("/bookings/9999");
            assertError(response, HttpStatus.NOT_FOUND, "NOT_FOUND");
            assertThat(response.getBody().get("path").asText()).isEqualTo("/bookings/9999");
            assertThat(response.getBody().get("requestId").asText())
                    .isEqualTo(response.getHeaders().getFirst("X-Request-Id"));
        }

        @Test
        void unknownTechnicianIs404() {
            assertError(get("/technicians/9999/schedule?date=" + TOMORROW), HttpStatus.NOT_FOUND, "NOT_FOUND");
        }

        @Test
        void invalidBodyIs400WithFieldDetails() {
            ResponseEntity<JsonNode> response = book(JUMPSTART, "12AB", tomorrowAt("10:00"), "call-me");
            assertError(response, HttpStatus.BAD_REQUEST, "VALIDATION_ERROR");
            assertThat(response.getBody().get("details")).extracting(d -> d.get("field").asText())
                    .containsExactlyInAnyOrder("pincode", "customerPhone");
        }

        @Test
        void malformedJsonIs400() {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            ResponseEntity<JsonNode> response = http.exchange("/bookings", HttpMethod.POST,
                    new HttpEntity<>("{\"serviceId\": 2, \"startTime\": \"tomorrow-ish\"}", headers), JsonNode.class);
            assertError(response, HttpStatus.BAD_REQUEST, "VALIDATION_ERROR");
        }

        @Test
        void missingOrBadQueryParamsAre400() {
            assertError(get("/availability?serviceId=2&pincode=" + ZONE_EAST_PINCODE),
                    HttpStatus.BAD_REQUEST, "VALIDATION_ERROR");
            assertError(get("/availability?serviceId=2&pincode=" + ZONE_EAST_PINCODE + "&date=08-01-2030"),
                    HttpStatus.BAD_REQUEST, "VALIDATION_ERROR");
            assertError(get("/availability?serviceId=2&pincode=abc&date=" + TOMORROW),
                    HttpStatus.BAD_REQUEST, "VALIDATION_ERROR");
        }

        @Test
        void unknownServiceOrPincodeIs422() {
            assertError(book(99, ZONE_EAST_PINCODE, tomorrowAt("10:00"), PHONE),
                    HttpStatus.UNPROCESSABLE_ENTITY, "UNKNOWN_SERVICE");
            assertError(book(JUMPSTART, "110001", tomorrowAt("10:00"), PHONE),
                    HttpStatus.UNPROCESSABLE_ENTITY, "UNSERVICEABLE_PINCODE");
            assertError(get("/availability?serviceId=2&pincode=110001&date=" + TOMORROW),
                    HttpStatus.UNPROCESSABLE_ENTITY, "UNSERVICEABLE_PINCODE");
        }

        @Test
        void startOffTheHalfHourIs422() {
            assertError(book(JUMPSTART, ZONE_EAST_PINCODE, tomorrowAt("10:15"), PHONE),
                    HttpStatus.UNPROCESSABLE_ENTITY, "SLOT_NOT_ALIGNED");
        }

        @Test
        void startOutsideBookingWindowIs422() {
            // NOW is 06:00 today, so 07:30 today is under 2 hours away.
            assertError(book(JUMPSTART, ZONE_EAST_PINCODE, "2030-01-07T07:30:00+05:30", PHONE),
                    HttpStatus.UNPROCESSABLE_ENTITY, "OUTSIDE_BOOKING_WINDOW");
            assertError(book(JUMPSTART, ZONE_EAST_PINCODE, "2030-01-15T10:00:00+05:30", PHONE),
                    HttpStatus.UNPROCESSABLE_ENTITY, "OUTSIDE_BOOKING_WINDOW");
        }

        @Test
        void outsideShiftHoursIsNoAvailability() {
            assertError(book(JUMPSTART, ZONE_EAST_PINCODE, tomorrowAt("20:00"), PHONE),
                    HttpStatus.CONFLICT, "NO_AVAILABILITY");
        }

        @Test
        void takenSlotIs409() {
            book(JUMPSTART, ZONE_EAST_PINCODE, tomorrowAt("10:00"), PHONE);
            assertError(book(JUMPSTART, ZONE_EAST_PINCODE, tomorrowAt("10:30"), "+919000000001"),
                    HttpStatus.CONFLICT, "NO_AVAILABILITY");
        }

        @Test
        void cancellingInsideTheLastHourIs409() {
            long id = book(JUMPSTART, ZONE_EAST_PINCODE, tomorrowAt("10:00"), PHONE).getBody().get("id").asLong();
            clock.set(NOW.plus(Duration.ofHours(27)).plus(Duration.ofMinutes(1))); // tomorrow 09:01

            assertError(post("/bookings/" + id + "/cancel"), HttpStatus.CONFLICT, "CANCELLATION_WINDOW_CLOSED");
            assertThat(get("/bookings/" + id).getBody().get("status").asText()).isEqualTo("CONFIRMED");
        }

        @Test
        void unknownBookingCancelIs404() {
            assertError(post("/bookings/9999/cancel"), HttpStatus.NOT_FOUND, "NOT_FOUND");
        }
    }

    @Nested
    class ListByPhone {

        @Test
        void pagesThroughACustomersBookingsNewestFirst() {
            book(JUMPSTART, ZONE_EAST_PINCODE, tomorrowAt("09:00"), PHONE);
            book(JUMPSTART, ZONE_EAST_PINCODE, tomorrowAt("11:00"), PHONE);
            book(JUMPSTART, ZONE_EAST_PINCODE, tomorrowAt("13:00"), PHONE);
            book(JUMPSTART, ZONE_EAST_PINCODE, tomorrowAt("15:00"), "+919000000001");

            JsonNode firstPage = getEncoded("/bookings?phone=%2B919876543210&size=2").getBody();
            assertThat(firstPage.get("totalItems").asLong()).isEqualTo(3);
            assertThat(firstPage.get("totalPages").asInt()).isEqualTo(2);
            assertThat(firstPage.get("items")).extracting(b -> b.get("startTime").asText())
                    .containsExactly(tomorrowAt("13:00"), tomorrowAt("11:00"));

            JsonNode secondPage = getEncoded("/bookings?phone=%2B919876543210&size=2&page=1").getBody();
            assertThat(secondPage.get("items")).extracting(b -> b.get("startTime").asText())
                    .containsExactly(tomorrowAt("09:00"));
        }

        @Test
        void filtersByStatus() {
            long id = book(JUMPSTART, ZONE_EAST_PINCODE, tomorrowAt("09:00"), PHONE).getBody().get("id").asLong();
            book(JUMPSTART, ZONE_EAST_PINCODE, tomorrowAt("11:00"), PHONE);
            post("/bookings/" + id + "/cancel");

            JsonNode cancelled = getEncoded("/bookings?phone=%2B919876543210&status=CANCELLED").getBody();
            assertThat(cancelled.get("items")).extracting(b -> b.get("id").asLong()).containsExactly(id);
        }
    }

    @Test
    void healthAndApiDocsAreServed() {
        ResponseEntity<JsonNode> health = get("/actuator/health");
        assertThat(health.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(health.getBody().get("status").asText()).isEqualTo("UP");

        JsonNode openApi = get("/v3/api-docs").getBody();
        assertThat(openApi.get("paths").has("/bookings")).isTrue();
        assertThat(openApi.get("paths").has("/availability")).isTrue();
    }

    @Test
    void echoesCallerRequestId() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Request-Id", "trace-abc-123");
        ResponseEntity<JsonNode> response = http.exchange("/bookings/9999", HttpMethod.GET,
                new HttpEntity<>(headers), JsonNode.class);
        assertThat(response.getHeaders().getFirst("X-Request-Id")).isEqualTo("trace-abc-123");
        assertThat(response.getBody().get("requestId").asText()).isEqualTo("trace-abc-123");
    }

    private static long technicianFor(ResponseEntity<JsonNode> response) {
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody().get("technician").get("id").asLong();
    }

    private static void assertError(ResponseEntity<JsonNode> response, HttpStatus status, String code) {
        assertThat(response.getStatusCode()).isEqualTo(status);
        assertThat(response.getBody().get("code").asText()).isEqualTo(code);
        assertThat(response.getBody().get("status").asInt()).isEqualTo(status.value());
        assertThat(response.getBody().get("message").asText()).isNotBlank();
    }
}
