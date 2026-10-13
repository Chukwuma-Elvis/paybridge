package com.academy.paybridge.security;

import com.academy.paybridge.security.service.RateLimiter;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RateLimiterTest {

    private final MutableClock clock = new MutableClock();

    @Test
    void aCallerCanUseUpToTheLimitInAnInstant() {
        RateLimiter limiter = new RateLimiter(3, clock);

        assertThat(limiter.tryAcquire("a").allowed()).isTrue();
        assertThat(limiter.tryAcquire("a").allowed()).isTrue();
        assertThat(limiter.tryAcquire("a").allowed()).isTrue();
    }

    @Test
    void thePermitAfterTheLimitIsRefusedWithAWaitTime() {
        RateLimiter limiter = new RateLimiter(3, clock);
        for (int i = 0; i < 3; i++) {
            limiter.tryAcquire("a");
        }

        RateLimiter.Decision refused = limiter.tryAcquire("a");

        assertThat(refused.allowed()).isFalse();
        assertThat(refused.retryAfterSeconds()).isBetween(1L, 60L);
    }

    @Test
    void theWaitTimeIsHowLongUntilOneTokenReturns() {
        RateLimiter limiter = new RateLimiter(6, clock); // one token every 10 seconds
        for (int i = 0; i < 6; i++) {
            limiter.tryAcquire("a");
        }

        assertThat(limiter.tryAcquire("a").retryAfterSeconds()).isEqualTo(10L);
    }

    @Test
    void tokensTrickleBackAsTimePasses() {
        RateLimiter limiter = new RateLimiter(6, clock); // one token every 10 seconds
        for (int i = 0; i < 6; i++) {
            limiter.tryAcquire("a");
        }
        assertThat(limiter.tryAcquire("a").allowed()).isFalse();

        clock.advance(Duration.ofSeconds(10));

        assertThat(limiter.tryAcquire("a").allowed()).isTrue();
        assertThat(limiter.tryAcquire("a").allowed()).isFalse();
    }

    @Test
    void aBucketNeverHoldsMoreThanTheLimitNoMatterHowLongItRests() {
        RateLimiter limiter = new RateLimiter(2, clock);

        clock.advance(Duration.ofHours(5));

        assertThat(limiter.tryAcquire("a").allowed()).isTrue();
        assertThat(limiter.tryAcquire("a").allowed()).isTrue();
        assertThat(limiter.tryAcquire("a").allowed()).isFalse();
    }

    @Test
    void eachCallerHasItsOwnBucket() {
        RateLimiter limiter = new RateLimiter(1, clock);

        assertThat(limiter.tryAcquire("a").allowed()).isTrue();
        assertThat(limiter.tryAcquire("a").allowed()).isFalse();
        assertThat(limiter.tryAcquire("b").allowed()).isTrue();
    }

    @Test
    void peekingDoesNotUseUpATokenButSeesWhenTheBucketIsEmpty() {
        RateLimiter limiter = new RateLimiter(2, clock);

        assertThat(limiter.peek("a").allowed()).isTrue();
        assertThat(limiter.peek("a").allowed()).isTrue();
        assertThat(limiter.peek("a").allowed()).isTrue();

        limiter.tryAcquire("a");
        limiter.tryAcquire("a");

        assertThat(limiter.peek("a").allowed()).isFalse();
    }

    @Test
    void aLimitBelowOneIsRejected() {
        assertThatThrownBy(() -> new RateLimiter(0, clock)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void manyThreadsAtOnceNeverGetMoreThanTheLimit() throws Exception {
        RateLimiter limiter = new RateLimiter(50, clock);
        int threads = 200;
        java.util.concurrent.atomic.AtomicInteger allowed = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.CountDownLatch startingGun = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch finished = new java.util.concurrent.CountDownLatch(threads);

        for (int i = 0; i < threads; i++) {
            Thread.ofVirtual().start(() -> {
                try {
                    startingGun.await();
                    if (limiter.tryAcquire("shared").allowed()) {
                        allowed.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    finished.countDown();
                }
            });
        }
        startingGun.countDown();
        finished.await();

        assertThat(allowed.get()).isEqualTo(50);
    }
}
