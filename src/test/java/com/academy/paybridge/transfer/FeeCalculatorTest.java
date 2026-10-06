package com.academy.paybridge.transfer;

import com.academy.paybridge.shared.money.Money;
import com.academy.paybridge.transfer.service.FeeCalculator;
import com.academy.paybridge.transfer.service.TransferProperties;
import com.academy.paybridge.transfer.service.TransferProperties.FeeBand;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FeeCalculatorTest {

    private static FeeCalculator calculator(FeeBand... bands) {
        return new FeeCalculator(new TransferProperties(10_000, List.of(bands)));
    }

    private static FeeCalculator standard() {
        return calculator(new FeeBand(500_000L, 1_000), new FeeBand(5_000_000L, 2_500), new FeeBand(null, 5_000));
    }

    @ParameterizedTest
    @CsvSource({
            "1,          1000",   // tiny amounts still pay the first band
            "500000,     1000",   // exactly the top of band 1 stays in band 1
            "500001,     2500",   // one kobo more moves to band 2
            "5000000,    2500",   // exactly the top of band 2
            "5000001,    5000",   // one kobo more moves to the open-ended band
            "999999999,  5000"
    })
    void picksTheBandTheAmountFallsIn(long amountMinor, long expectedFeeMinor) {
        assertThat(standard().feeFor(Money.ngn(amountMinor))).isEqualTo(Money.ngn(expectedFeeMinor));
    }

    @Test
    void bandsMayBeListedInAnyOrder() {
        FeeCalculator shuffled = calculator(new FeeBand(null, 5_000), new FeeBand(500_000L, 1_000), new FeeBand(5_000_000L, 2_500));

        assertThat(shuffled.feeFor(Money.ngn(200_000))).isEqualTo(Money.ngn(1_000));
        assertThat(shuffled.feeFor(Money.ngn(9_000_000))).isEqualTo(Money.ngn(5_000));
    }

    @Test
    void aSingleOpenEndedBandIsAFlatFee() {
        assertThat(calculator(new FeeBand(null, 3_000)).feeFor(Money.ngn(123_456))).isEqualTo(Money.ngn(3_000));
    }

    @Test
    void refusesToStartWithoutAnyBands() {
        assertThatThrownBy(() -> new FeeCalculator(new TransferProperties(10_000, List.of())))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new FeeCalculator(new TransferProperties(10_000, null)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void refusesToStartIfSomeAmountWouldHaveNoFee() {
        assertThatThrownBy(() -> calculator(new FeeBand(500_000L, 1_000), new FeeBand(5_000_000L, 2_500)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("last fee band");
    }
}
