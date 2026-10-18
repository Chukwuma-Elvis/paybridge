package com.academy.paybridge.fx.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;

/**
 * The rules about rates, as small pure functions so each boundary can be tested exactly. No clock, no database.
 * <p>
 * Rates are exact decimals (six places). A floating-point number is never used: it cannot hold 0.1 exactly, and a rate is
 * multiplied into customers' money.
 */
public final class RatePolicy {

    /** Rates are stored and used to this many decimal places. */
    public static final int SCALE = 6;

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final BigDecimal TEN_THOUSAND = BigDecimal.valueOf(10_000);

    private RatePolicy() {
    }

    /** True when the rate is MORE than {@code maxAge} old. A rate exactly {@code maxAge} old is still usable. */
    public static boolean isStale(Instant asOf, Instant now, Duration maxAge) {
        return Duration.between(asOf, now).compareTo(maxAge) > 0;
    }

    /** True when the rate is between the believable limits, inclusive. */
    public static boolean isPlausible(BigDecimal rate, BigDecimal min, BigDecimal max) {
        return rate != null && rate.signum() > 0 && rate.compareTo(min) >= 0 && rate.compareTo(max) <= 0;
    }

    /** True when the new rate differs from the previous one by MORE than the threshold percentage. Exactly the threshold is fine. */
    public static boolean isSpike(BigDecimal previous, BigDecimal next, BigDecimal thresholdPercent) {
        BigDecimal changePercent = next.subtract(previous).abs()
                .multiply(HUNDRED)
                .divide(previous, 10, RoundingMode.HALF_UP);
        return changePercent.compareTo(thresholdPercent) > 0;
    }

    /** What the customer gets: the mid rate less our spread, rounded DOWN so the house never gives away a fraction. */
    public static BigDecimal customerRate(BigDecimal midRate, int spreadBps) {
        if (spreadBps < 0 || spreadBps > 10_000) {
            throw new IllegalArgumentException("spread must be between 0 and 10000 basis points");
        }
        return midRate.multiply(TEN_THOUSAND.subtract(BigDecimal.valueOf(spreadBps)))
                .divide(TEN_THOUSAND, SCALE, RoundingMode.DOWN);
    }

    /** A rate at the standard number of decimal places (half-even, the usual rule for rates). */
    public static BigDecimal normalise(BigDecimal rate) {
        return rate.setScale(SCALE, RoundingMode.HALF_EVEN);
    }
}
