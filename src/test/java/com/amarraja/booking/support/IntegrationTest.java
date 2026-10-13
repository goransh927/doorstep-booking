package com.amarraja.booking.support;

import com.fasterxml.jackson.databind.JsonNode;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Boots the whole app on a random port against a real PostgreSQL (embedded binaries, no Docker),
 * with Flyway migrations and seed data applied, and a clock the test controls.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"booking.rate-limit.enabled=false", "spring.datasource.hikari.maximum-pool-size=20"})
@Import(IntegrationTest.TestClockConfig.class)
public abstract class IntegrationTest {

    protected static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    /** Monday 07 Jan 2030, 06:00 IST. Tomorrow is fully inside the 2h..7d booking window. */
    protected static final Instant NOW = LocalDateTime.parse("2030-01-07T06:00").atZone(IST).toInstant();
    protected static final LocalDate TOMORROW = LocalDate.parse("2030-01-08");

    protected static final long BATTERY_REPLACEMENT = 1;
    protected static final long JUMPSTART = 2;
    protected static final String ZONE_SOUTH_PINCODE = "560034";
    protected static final String ZONE_EAST_PINCODE = "560037";
    protected static final long KIRAN = 5; // the only jump-start technician in zone East

    private static final EmbeddedPostgres POSTGRES = startPostgres();

    @Autowired
    protected TestRestTemplate http;
    @Autowired
    protected JdbcClient jdbc;
    @Autowired
    protected MutableClock clock;

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> POSTGRES.getJdbcUrl("postgres", "postgres"));
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "postgres");
    }

    @BeforeEach
    void resetState() {
        jdbc.sql("TRUNCATE booking RESTART IDENTITY").update();
        clock.set(NOW);
    }

    protected static String tomorrowAt(String time) {
        return TOMORROW + "T" + time + ":00+05:30";
    }

    protected ResponseEntity<JsonNode> book(long serviceId, String pincode, String startTime, String phone) {
        return book(serviceId, pincode, startTime, phone, null);
    }

    protected ResponseEntity<JsonNode> book(long serviceId, String pincode, String startTime, String phone,
                                            String idempotencyKey) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (idempotencyKey != null) {
            headers.set("Idempotency-Key", idempotencyKey);
        }
        Map<String, Object> body = Map.of("serviceId", serviceId, "pincode", pincode,
                "startTime", startTime, "customerPhone", phone);
        return http.exchange("/bookings", HttpMethod.POST, new HttpEntity<>(body, headers), JsonNode.class);
    }

    protected ResponseEntity<JsonNode> get(String path) {
        return http.getForEntity(path, JsonNode.class);
    }

    /** For paths whose query is already percent-encoded (a String path would be encoded again). */
    protected ResponseEntity<JsonNode> getEncoded(String path) {
        return http.getForEntity(URI.create(path), JsonNode.class);
    }

    protected ResponseEntity<JsonNode> post(String path) {
        return http.exchange(path, HttpMethod.POST, HttpEntity.EMPTY, JsonNode.class);
    }

    protected long bookingCount() {
        return jdbc.sql("SELECT count(*) FROM booking").query(Long.class).single();
    }

    private static EmbeddedPostgres startPostgres() {
        try {
            EmbeddedPostgres postgres = EmbeddedPostgres.builder().start();
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    postgres.close();
                } catch (IOException ignored) {
                    // JVM is exiting anyway
                }
            }));
            return postgres;
        } catch (IOException e) {
            throw new UncheckedIOException("Could not start embedded PostgreSQL", e);
        }
    }

    @TestConfiguration
    static class TestClockConfig {

        @Bean
        @Primary
        MutableClock testClock() {
            return new MutableClock(NOW);
        }
    }
}
