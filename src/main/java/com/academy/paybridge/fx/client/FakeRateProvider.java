package com.academy.paybridge.fx.client;

import com.academy.paybridge.fx.api.Corridor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A rate source for development and tests whose behaviour can be scripted, so every awkward case can be forced on demand:
 * a steady rate, a rate that has gone stale, a sudden jump, an outage.
 * <p>
 * Selected by {@code paybridge.fx.provider=fake} (the default). A real source would be another class behind {@link RateProvider}.
 */
@Component
@ConditionalOnProperty(name = "paybridge.fx.provider", havingValue = "fake", matchIfMissing = true)
public class FakeRateProvider implements RateProvider {

    private static final BigDecimal STARTING_RATE = new BigDecimal("1500.000000");

    private final Clock clock;
    private final AtomicReference<BigDecimal> midRate = new AtomicReference<>(STARTING_RATE);
    private final AtomicReference<Duration> age = new AtomicReference<>(Duration.ZERO);
    private final AtomicReference<Boolean> down = new AtomicReference<>(false);

    public FakeRateProvider(Clock clock) {
        this.clock = clock;
    }

    @Override
    public ProviderRate fetch(Corridor corridor) {
        if (down.get()) {
            throw new RateProviderUnavailableException("The (fake) rate source is down");
        }
        return new ProviderRate(midRate.get(), "fake", clock.instant().minus(age.get()));
    }

    /** Scripts the rate the source reports from now on. */
    public void setMidRate(BigDecimal rate) {
        midRate.set(rate);
    }

    /** Scripts how old the reported rate is (a source that keeps reporting an old value). */
    public void setReportedAge(Duration reportedAge) {
        age.set(reportedAge);
    }

    /** Scripts an outage. */
    public void setDown(boolean isDown) {
        down.set(isDown);
    }

    /** Back to a healthy, fresh source at the starting rate. */
    public void reset() {
        midRate.set(STARTING_RATE);
        age.set(Duration.ZERO);
        down.set(false);
    }

    /** The as-of moment this source would report right now. */
    public Instant reportedAsOf() {
        return clock.instant().minus(age.get());
    }
}
