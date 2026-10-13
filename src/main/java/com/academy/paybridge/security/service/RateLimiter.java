package com.academy.paybridge.security.service;

import java.time.Clock;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A "token bucket" rate limiter. Each caller (a key id, or an address) has a bucket that holds up to
 * {@code permitsPerMinute} tokens. Every request takes one token; tokens trickle back at a steady rate. An empty bucket
 * means "slow down".
 * <p>
 * Because the bucket starts full and refills steadily, a caller can burst up to the limit and then settles to the
 * average rate, which suits real traffic better than a hard "N per calendar minute" cut-off.
 * <p>
 * State lives in memory: right for one running copy of the app. With several copies, each would count separately
 * (a shared store such as Redis would be needed).
 */
public final class RateLimiter {

    /** The answer to "may this request go ahead?", and if not, how long until it may. */
    public record Decision(boolean allowed, long retryAfterSeconds) {
        static final Decision ALLOWED = new Decision(true, 0);
    }

    private static final int PURGE_ABOVE_ENTRIES = 10_000;
    private static final long IDLE_MILLIS_BEFORE_FORGET = 120_000;

    private final int permitsPerMinute;
    private final Clock clock;
    private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();

    public RateLimiter(int permitsPerMinute, Clock clock) {
        if (permitsPerMinute < 1) {
            throw new IllegalArgumentException("permitsPerMinute must be at least 1");
        }
        this.permitsPerMinute = permitsPerMinute;
        this.clock = clock;
    }

    /** Takes one token for this caller if there is one. */
    public Decision tryAcquire(String key) {
        long now = clock.millis();
        forgetIdleCallersIfTooMany(now);
        return buckets.computeIfAbsent(key, k -> new Bucket(permitsPerMinute, now)).take(now);
    }

    /** Looks without taking: would a request from this caller be allowed right now? */
    public Decision peek(String key) {
        Bucket bucket = buckets.get(key);
        return bucket == null ? Decision.ALLOWED : bucket.look(clock.millis());
    }

    private void forgetIdleCallersIfTooMany(long now) {
        if (buckets.size() > PURGE_ABOVE_ENTRIES) {
            buckets.values().removeIf(bucket -> bucket.idleSince(now) > IDLE_MILLIS_BEFORE_FORGET);
        }
    }

    private final class Bucket {

        private double tokens;
        private long lastRefillMillis;

        Bucket(double tokens, long now) {
            this.tokens = tokens;
            this.lastRefillMillis = now;
        }

        synchronized Decision take(long now) {
            refill(now);
            if (tokens >= 1) {
                tokens -= 1;
                return Decision.ALLOWED;
            }
            return denied();
        }

        synchronized Decision look(long now) {
            refill(now);
            return tokens >= 1 ? Decision.ALLOWED : denied();
        }

        synchronized long idleSince(long now) {
            return now - lastRefillMillis;
        }

        private void refill(long now) {
            long elapsed = Math.max(0, now - lastRefillMillis);
            tokens = Math.min(permitsPerMinute, tokens + elapsed * permitsPerMinute / 60_000.0);
            lastRefillMillis = now;
        }

        private Decision denied() {
            double secondsUntilOneToken = (1 - tokens) * 60.0 / permitsPerMinute;
            return new Decision(false, Math.max(1, (long) Math.ceil(secondsUntilOneToken)));
        }
    }
}
