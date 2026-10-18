package com.academy.paybridge.fx.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConversionCalculatorTest {

    private static BigDecimal d(String value) {
        return new BigDecimal(value);
    }

    // ---- converting

    @ParameterizedTest
    @CsvSource({
            "100000,   1485,        148500000",     // USD 1,000.00 at 1485 -> NGN 1,485,000.00
            "1,        1485,        1485",           // one cent is 1485 kobo
            "0,        1485,        0",
            "333,      1485.5,      494671",         // 333 x 1485.5 = 494,671.5 -> down
            "1,        0.999999,    0",              // less than one kobo is nothing, never rounded up
            "7,        1485.123456, 10395",          // 7 x 1485.123456 = 10395.864192 -> down
            "250050,   1462.5,      365698125",      // 250050 x 1462.5 = 365,698,125 exactly
            "100,      1500.000001, 150000"          // 100 x 1500.000001 = 150000.0001 -> down
    })
    void theConvertedAmountIsRoundedDown(long sell, String rate, long expected) {
        assertThat(ConversionCalculator.convert(sell, d(rate))).isEqualTo(expected);
    }

    @Test
    void theResultIsNeverMoreThanTheExactValue() {
        Random random = new Random(42);
        for (int i = 0; i < 20_000; i++) {
            long sell = 1 + random.nextInt(100_000_000);
            BigDecimal rate = BigDecimal.valueOf(100 + random.nextInt(4_900_000), 3).setScale(6, RoundingMode.UNNECESSARY);
            BigDecimal exact = BigDecimal.valueOf(sell).multiply(rate);

            long converted = ConversionCalculator.convert(sell, rate);

            assertThat(BigDecimal.valueOf(converted)).isLessThanOrEqualTo(exact);
            assertThat(exact.subtract(BigDecimal.valueOf(converted))).as("never loses a whole unit").isLessThan(BigDecimal.ONE);
        }
    }

    @Test
    void moreSoldNeverBuysLess() {
        Random random = new Random(7);
        BigDecimal rate = d("1485.123456");
        long previous = 0;
        for (long sell = 0; sell < 5_000; sell += 1 + random.nextInt(3)) {
            long converted = ConversionCalculator.convert(sell, rate);
            assertThat(converted).isGreaterThanOrEqualTo(previous);
            previous = converted;
        }
    }

    @Test
    void convertingBackNeverGivesMoreThanWasSold() {
        // the house never creates money by converting there and back at the same rate
        Random random = new Random(99);
        for (int i = 0; i < 5_000; i++) {
            long sell = 1 + random.nextInt(50_000_000);
            BigDecimal rate = BigDecimal.valueOf(1000 + random.nextInt(1_000_000), 3).setScale(6, RoundingMode.UNNECESSARY);

            long kobo = ConversionCalculator.convert(sell, rate);
            long backInCents = BigDecimal.valueOf(kobo).divide(rate, 0, RoundingMode.DOWN).longValueExact();

            assertThat(backInCents).isLessThanOrEqualTo(sell);
        }
    }

    @Test
    void theLargestAmountsThatFitAreConvertedExactly() {
        assertThat(ConversionCalculator.convert(1_000_000_000_000L, d("1500"))).isEqualTo(1_500_000_000_000_000L);
    }

    @Test
    void aResultThatDoesNotFitInALongIsAnErrorNotAWrongNumber() {
        assertThatThrownBy(() -> ConversionCalculator.convert(Long.MAX_VALUE, d("1500")))
                .isInstanceOf(ArithmeticException.class);
    }

    @Test
    void negativeAmountsAndNonPositiveRatesAreRefused() {
        assertThatThrownBy(() -> ConversionCalculator.convert(-1, d("1500"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ConversionCalculator.convert(100, d("0"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ConversionCalculator.convert(100, d("-1"))).isInstanceOf(IllegalArgumentException.class);
    }

    // ---- the fee

    @ParameterizedTest
    @CsvSource({
            "100000, 50, 100, 500",     // 0.5% of USD 1,000.00 = USD 5.00
            "10000,  50, 100, 100",     // 0.5% of USD 100.00 = 50 cents, so the USD 1.00 minimum applies
            "20001,  50, 100, 101",     // 0.5% of 20001 = 100.005 -> rounds UP to 101
            "20000,  50, 100, 100",     // exactly 100
            "1,      50, 100, 100",     // tiny amounts still pay the minimum
            "0,      50, 100, 100",
            "100000, 0,  0,   0",       // no fee configured
            "100000, 50, 0,   500"
    })
    void theFeeIsRoundedUpAndNeverBelowTheMinimum(long sell, int bps, long min, long expected) {
        assertThat(ConversionCalculator.fee(sell, bps, min)).isEqualTo(expected);
    }

    @Test
    void theFeeIsNeverLessThanTheExactPercentage() {
        Random random = new Random(11);
        for (int i = 0; i < 20_000; i++) {
            long sell = random.nextInt(500_000_000);
            int bps = random.nextInt(500);

            long fee = ConversionCalculator.fee(sell, bps, 0);

            BigDecimal exact = BigDecimal.valueOf(sell).multiply(BigDecimal.valueOf(bps)).divide(BigDecimal.valueOf(10_000));
            assertThat(BigDecimal.valueOf(fee)).isGreaterThanOrEqualTo(exact);
            assertThat(BigDecimal.valueOf(fee).subtract(exact)).as("overcharges by less than one unit").isLessThan(BigDecimal.ONE);
        }
    }

    @Test
    void negativeInputsToTheFeeAreRefused() {
        assertThatThrownBy(() -> ConversionCalculator.fee(-1, 50, 100)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ConversionCalculator.fee(100, -1, 100)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ConversionCalculator.fee(100, 50, -1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aFeeThatCannotBeRepresentedIsAnErrorNotAWrongNumber() {
        assertThatThrownBy(() -> ConversionCalculator.fee(Long.MAX_VALUE, 20_000, 0)).isInstanceOf(ArithmeticException.class);
    }
}
