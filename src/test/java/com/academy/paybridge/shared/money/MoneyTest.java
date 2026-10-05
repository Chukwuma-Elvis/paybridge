package com.academy.paybridge.shared.money;

import com.academy.paybridge.shared.exception.CurrencyMismatchException;
import org.junit.jupiter.api.Test;

import java.util.Currency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MoneyTest {

    @Test
    void addsTwoAmountsInTheSameCurrency() {
        Money result = Money.ngn(50_000).add(Money.ngn(25_000));

        assertThat(result).isEqualTo(Money.ngn(75_000));
    }

    @Test
    void refusesToAddDifferentCurrencies() {
        Money naira = Money.ngn(50_000);
        Money dollars = Money.ofMinor(500, Currency.getInstance("USD"));

        assertThatThrownBy(() -> naira.add(dollars))
                .isInstanceOf(CurrencyMismatchException.class);
    }

    @Test
    void canGiveNegativeWhenSubtracting() {
        Money result = Money.ngn(250).subtract(Money.ngn(500));

        assertThat(result).isEqualTo(Money.ngn(-250));
    }

    @Test
    void rejectsNullCurrency() {
        assertThatThrownBy(() -> new Money(100, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("must not be null");
    }

    @Test
    void canCreateZero(){
       Money zero = Money.zero(Money.NGN);
       assertThat(zero.isZero()).isTrue();

    }

    @Test
    void addingPastTheMaximumThrows() {
        Money max = Money.ngn(Long.MAX_VALUE);

        assertThatThrownBy(() -> max.add(Money.ngn(1)))
                .isInstanceOf(ArithmeticException.class);
    }

    @Test
    void subtractingBelowTheMinimumThrows() {
        Money min = Money.ngn(Long.MIN_VALUE);

        assertThatThrownBy(() -> min.subtract(Money.ngn(1)))
                .isInstanceOf(ArithmeticException.class);
    }

    @Test
    void negatingTheMinimumThrows() {
        assertThatThrownBy(() -> Money.ngn(Long.MIN_VALUE).negate())
                .isInstanceOf(ArithmeticException.class);
    }

    @Test
    void additionAtTheMaximumBoundaryStillWorks() {
        Money almostMax = Money.ngn(Long.MAX_VALUE - 1);

        assertThat(almostMax.add(Money.ngn(1))).isEqualTo(Money.ngn(Long.MAX_VALUE));
    }

}
