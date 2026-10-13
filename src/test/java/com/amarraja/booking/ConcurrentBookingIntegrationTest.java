package com.amarraja.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.amarraja.booking.support.IntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.IntFunction;
import java.util.stream.Collectors;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * Fires many identical booking requests at the same instant over real HTTP and checks the
 * outcome is exactly what the rules allow. Repeated to shake out timing-dependent passes.
 */
class ConcurrentBookingIntegrationTest extends IntegrationTest {

    private static final int THREADS = 20;

    @RepeatedTest(5)
    void racingForTheLastTechnicianExactlyOneWins() throws Exception {
        // Kiran is the only jump-start technician in zone East.
        List<ResponseEntity<JsonNode>> responses = race(i ->
                () -> book(JUMPSTART, ZONE_EAST_PINCODE, tomorrowAt("10:00"), phone(i)));

        Map<HttpStatus, Long> byStatus = countByStatus(responses);
        assertThat(byStatus).containsEntry(HttpStatus.CREATED, 1L)
                .containsEntry(HttpStatus.CONFLICT, (long) THREADS - 1)
                .hasSize(2);
        responses.stream()
                .filter(r -> r.getStatusCode() == HttpStatus.CONFLICT)
                .forEach(r -> assertThat(r.getBody().get("code").asText()).isEqualTo("NO_AVAILABILITY"));
        assertThat(bookingCount()).isEqualTo(1);
    }

    @RepeatedTest(5)
    void racingForTwoTechniciansGivesEachToExactlyOneCustomer() throws Exception {
        // Zone East battery fitters: Priya (4) and Meena (6).
        List<ResponseEntity<JsonNode>> responses = race(i ->
                () -> book(BATTERY_REPLACEMENT, ZONE_EAST_PINCODE, tomorrowAt("10:00"), phone(i)));

        List<Long> assigned = responses.stream()
                .filter(r -> r.getStatusCode() == HttpStatus.CREATED)
                .map(r -> r.getBody().get("technician").get("id").asLong())
                .toList();
        assertThat(assigned).containsExactlyInAnyOrder(4L, 6L);
        assertThat(countByStatus(responses)).containsEntry(HttpStatus.CONFLICT, (long) THREADS - 2);
    }

    @RepeatedTest(3)
    void concurrentRetriesWithOneIdempotencyKeyCreateOneBooking() throws Exception {
        List<ResponseEntity<JsonNode>> responses = race(i ->
                () -> book(BATTERY_REPLACEMENT, ZONE_SOUTH_PINCODE, tomorrowAt("10:00"), phone(0), "same-key"));

        assertThat(responses).allSatisfy(r -> assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED));
        assertThat(responses.stream().map(r -> r.getBody().get("id").asLong()).distinct()).hasSize(1);
        assertThat(bookingCount()).isEqualTo(1);
    }

    @Test
    void databaseRejectsOverlapEvenIfApplicationChecksAreBypassed() {
        book(JUMPSTART, ZONE_EAST_PINCODE, tomorrowAt("10:00"), phone(0));

        // 10:30 starts inside the first job's travel buffer (10:30-11:00).
        assertThatThrownBy(() -> jdbc.sql("""
                        INSERT INTO booking (customer_phone, service_id, pincode, technician_id, start_time,
                                             end_time, blocked_until, status, created_at, updated_at)
                        VALUES ('+919000000099', 2, '560037', 5, '2030-01-08 10:30+05:30',
                                '2030-01-08 11:00+05:30', '2030-01-08 11:30+05:30', 'CONFIRMED', now(), now())
                        """).update())
                .rootCause()
                .isInstanceOf(SQLException.class)
                .satisfies(e -> assertThat(((SQLException) e).getSQLState()).isEqualTo("23P01"));
    }

    private List<ResponseEntity<JsonNode>> race(IntFunction<Callable<ResponseEntity<JsonNode>>> request)
            throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        try {
            CountDownLatch ready = new CountDownLatch(THREADS);
            CountDownLatch go = new CountDownLatch(1);
            List<Future<ResponseEntity<JsonNode>>> futures = new ArrayList<>();
            for (int i = 0; i < THREADS; i++) {
                Callable<ResponseEntity<JsonNode>> call = request.apply(i);
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return call.call();
                }));
            }
            ready.await();
            go.countDown();
            List<ResponseEntity<JsonNode>> responses = new ArrayList<>();
            for (Future<ResponseEntity<JsonNode>> future : futures) {
                responses.add(future.get());
            }
            return responses;
        } finally {
            pool.shutdownNow();
        }
    }

    private static Map<HttpStatus, Long> countByStatus(List<ResponseEntity<JsonNode>> responses) {
        return responses.stream()
                .collect(Collectors.groupingBy(r -> HttpStatus.valueOf(r.getStatusCode().value()),
                        Collectors.counting()));
    }

    private static String phone(int i) {
        return String.format("+9190000%05d", i);
    }
}
