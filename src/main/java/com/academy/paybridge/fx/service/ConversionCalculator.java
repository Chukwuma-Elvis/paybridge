package com.academy.paybridge.fx.service;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * The one place where an amount of money is multiplied by a rate. Exact decimal arithmetic throughout; a floating-point number
 * never touches money.
 * <p>
 * The rounding rule is deliberate and favours the house, never the customer:
 * <ul>
 *   <li>what we PAY OUT (the converted amount) is rounded DOWN;</li>
 *   <li>what we CHARGE (the fee) is rounded UP.</li>
 * </ul>
 * Amounts are whole minor units (cents, kobo). Because both USD and NGN have 100 minor units to the major unit, converting cents to
 * kobo is a single multiplication by the rate.
 * <p>
 * Methods throw {@link ArithmeticException} if a result does not fit in a {@code long}; callers turn that into a "too large" error.
 */
public final class ConversionCalculator {

    private static final BigDecimal TEN_THOUSAND = BigDecimal.valueOf(10_000);

    private ConversionCalculator() {
    }

    /** The amount received for {@code sellMinor} at {@code rate}, rounded DOWN to a whole minor unit. */
    public static long convert(long sellMinor, BigDecimal rate) {
        if (sellMinor < 0) {
            throw new IllegalArgumentException("the amount must not be negative");
        }
        if (rate.signum() <= 0) {
            throw new IllegalArgumentException("the rate must be positive");
        }
        return BigDecimal.valueOf(sellMinor).multiply(rate).setScale(0, RoundingMode.DOWN).longValueExact();
    }

    /**
     * Our fee for {@code sellMinor}: {@code feeBps} basis points of it, rounded UP, but never less than {@code minFeeMinor}.
     */
    public static long fee(long sellMinor, int feeBps, long minFeeMinor) {
        if (sellMinor < 0 || feeBps < 0 || minFeeMinor < 0) {
            throw new IllegalArgumentException("the amount, the fee rate and the minimum fee must not be negative");
        }
        long percentage = BigDecimal.valueOf(sellMinor).multiply(BigDecimal.valueOf(feeBps))
                .divide(TEN_THOUSAND, 0, RoundingMode.UP).longValueExact();
        return Math.max(percentage, minFeeMinor);
    }
}
