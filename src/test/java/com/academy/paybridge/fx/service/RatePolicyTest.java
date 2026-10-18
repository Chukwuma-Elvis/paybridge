package com.academy.paybridge.fx.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RatePolicyTest {

    private static final Instant NOW = Instant.parse("2026-01-01T12:00:00Z");
    private static final Duration FIVE_MINUTES = Duration.ofMinutes(5);

    private static BigDecimal d(String value) {
        return new BigDecimal(value);
    }

    // ---- staleness

    @Test
    void aFreshRateIsNotStale() {
        assertThat(RatePolicy.isStale(NOW.minusSeconds(10), NOW, FIVE_MINUTES)).isFalse();
    }

    @Test
    void aRateExactlyAtTheMaximumAgeIsStillUsable() {
        assertThat(RatePolicy.isStale(NOW.minus(FIVE_MINUTES), NOW, FIVE_MINUTES)).isFalse();
    }

    @Test
    void aRateOneNanosecondOverTheMaximumAgeIsStale() {
        assertThat(RatePolicy.isStale(NOW.minus(FIVE_MINUTES).minusNanos(1), NOW, FIVE_MINUTES)).isTrue();
    }

    @Test
    void aRateFromTheFutureIsNotStale() {
        assertThat(RatePolicy.isStale(NOW.plusSeconds(30), NOW, FIVE_MINUTES)).isFalse();
    }

    // ---- plausibility

    @ParameterizedTest
    @CsvSource({"100, true", "5000, true", "1500.5, true", "99.999999, false", "5000.000001, false", "0, false", "-1500, false"})
    void onlyRatesBetweenTheLimitsInclusiveAreBelievable(String rate, boolean expected) {
        assertThat(RatePolicy.isPlausible(d(rate), d("100"), d("5000"))).isEqualTo(expected);
    }

    @Test
    void aMissingRateIsNotBelievable() {
        assertThat(RatePolicy.isPlausible(null, d("100"), d("5000"))).isFalse();
    }

    // ---- spikes

    @ParameterizedTest
    @CsvSource({
            "1000, 1050, false",         // exactly +5%: allowed
            "1000, 950, false",          // exactly -5%: allowed
            "1000, 1050.000001, true",   // a hair over +5%
            "1000, 949.999999, true",    // a hair over -5%
            "1000, 1000, false",
            "1500, 1520, false",
            "1500, 1700, true",
            "1500, 100, true"
    })
    void aMoveBiggerThanTheThresholdIsASpike(String previous, String next, boolean expected) {
        assertThat(RatePolicy.isSpike(d(previous), d(next), d("5"))).isEqualTo(expected);
    }

    @Test
    void theThresholdIsConfigurable() {
        assertThat(RatePolicy.isSpike(d("1000"), d("1020"), d("1"))).isTrue();
        assertThat(RatePolicy.isSpike(d("1000"), d("1020"), d("2"))).isFalse();
    }

    // ---- the customer's rate

    @Test
    void theCustomerRateIsTheMidRateLessTheSpread() {
        assertThat(RatePolicy.customerRate(d("1500"), 100)).isEqualByComparingTo("1485");
        assertThat(RatePolicy.customerRate(d("1500"), 250)).isEqualByComparingTo("1462.5");
    }

    @Test
    void theCustomerRateIsRoundedDownNeverInTheCustomersFavour() {
        // 1500.123456 x 0.99 = 1485.12222144 -> rounds DOWN to 1485.122221 (half-up would also give ...221; this case proves down)
        assertThat(RatePolicy.customerRate(d("1500.123456"), 100)).isEqualByComparingTo("1485.122221");
        // 1000.000009 x 0.99 = 990.00000891 -> down gives 990.000008 (half-up would give 990.000009)
        assertThat(RatePolicy.customerRate(d("1000.000009"), 100)).isEqualByComparingTo("990.000008");
    }

    @Test
    void noSpreadMeansTheMidRate() {
        assertThat(RatePolicy.customerRate(d("1500.5"), 0)).isEqualByComparingTo("1500.5");
    }

    @Test
    void theCustomerRateKeepsSixDecimalPlaces() {
        assertThat(RatePolicy.customerRate(d("1500"), 100).scale()).isEqualTo(RatePolicy.SCALE);
    }

    @Test
    void aSpreadOutsideZeroToTenThousandIsRefused() {
        assertThatThrownBy(() -> RatePolicy.customerRate(d("1500"), -1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RatePolicy.customerRate(d("1500"), 10_001)).isInstanceOf(IllegalArgumentException.class);
    }

    // ---- normalising

    @Test
    void ratesAreKeptToSixPlacesRoundingHalfToEven() {
        assertThat(RatePolicy.normalise(d("1500.1234565"))).isEqualByComparingTo("1500.123456");   // ...56|5 -> 6 is even, stays
        assertThat(RatePolicy.normalise(d("1500.1234575"))).isEqualByComparingTo("1500.123458");   // ...57|5 -> 7 is odd, goes up
        assertThat(RatePolicy.normalise(d("1500")).scale()).isEqualTo(6);
    }
}
