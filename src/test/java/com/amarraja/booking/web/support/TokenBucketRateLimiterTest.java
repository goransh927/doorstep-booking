package com.amarraja.booking.web.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.amarraja.booking.support.MutableClock;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class TokenBucketRateLimiterTest {

    private final MutableClock clock = new MutableClock(Instant.parse("2030-01-08T00:00:00Z"));
    private final TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(3, 60, clock);

    @Test
    void allowsBurstUpToCapacityThenRejects() {
        assertThat(limiter.tryAcquire("a")).isTrue();
        assertThat(limiter.tryAcquire("a")).isTrue();
        assertThat(limiter.tryAcquire("a")).isTrue();
        assertThat(limiter.tryAcquire("a")).isFalse();
    }

    @Test
    void refillsOverTime() {
        for (int i = 0; i < 3; i++) {
            limiter.tryAcquire("a");
        }
        clock.advance(Duration.ofSeconds(1)); // 60 per minute = one per second
        assertThat(limiter.tryAcquire("a")).isTrue();
        assertThat(limiter.tryAcquire("a")).isFalse();
    }

    @Test
    void clientsHaveSeparateBuckets() {
        for (int i = 0; i < 3; i++) {
            limiter.tryAcquire("a");
        }
        assertThat(limiter.tryAcquire("b")).isTrue();
    }
}
