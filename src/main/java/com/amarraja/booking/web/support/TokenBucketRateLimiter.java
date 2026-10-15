package com.amarraja.booking.web.support;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class TokenBucketRateLimiter {

    private static final int MAX_TRACKED_CLIENTS = 10_000;

    private final int capacity;
    private final double tokensPerNano;
    private final Clock clock;
    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    public TokenBucketRateLimiter(int capacity, int refillPerMinute, Clock clock) {
        this.capacity = capacity;
        this.tokensPerNano = refillPerMinute / (double) Duration.ofMinutes(1).toNanos();
        this.clock = clock;
    }

    public boolean tryAcquire(String clientKey) {
        if (buckets.size() > MAX_TRACKED_CLIENTS) {
            evictFullBuckets();
        }
        return buckets.computeIfAbsent(clientKey, k -> new Bucket(capacity, nanos())).tryTake();
    }

    /** Seconds until one token is back, for the Retry-After header. */
    public long retryAfterSeconds() {
        return Math.max(1, (long) Math.ceil(1 / tokensPerNano / 1_000_000_000d));
    }

    private void evictFullBuckets() {
        long now = nanos();
        buckets.values().removeIf(b -> b.isFull(now));
    }

    private long nanos() {
        Duration sinceEpoch = Duration.ofMillis(clock.millis());
        return sinceEpoch.toNanos();
    }

    private final class Bucket {
        private double tokens;
        private long lastRefill;

        Bucket(double tokens, long now) {
            this.tokens = tokens;
            this.lastRefill = now;
        }

        synchronized boolean tryTake() {
            refill(nanos());
            if (tokens >= 1) {
                tokens -= 1;
                return true;
            }
            return false;
        }

        synchronized boolean isFull(long now) {
            refill(now);
            return tokens >= capacity;
        }

        private void refill(long now) {
            long elapsed = Math.max(0, now - lastRefill);
            tokens = Math.min(capacity, tokens + elapsed * tokensPerNano);
            lastRefill = now;
        }
    }
}
