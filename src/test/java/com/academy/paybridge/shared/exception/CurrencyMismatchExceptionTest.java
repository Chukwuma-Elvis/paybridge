package com.academy.paybridge.shared.exception;

import com.academy.paybridge.shared.money.Money;
import org.junit.jupiter.api.Test;

import java.util.Currency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CurrencyMismatchExceptionTest {

    private static final Currency NGN = Currency.getInstance("NGN");
    private static final Currency USD = Currency.getInstance("USD");

    @Test
    void usesTheCurrencyMismatchCode() {
        CurrencyMismatchException exception = new CurrencyMismatchException(NGN, USD);

        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.CURRENCY_MISMATCH);
    }

    @Test
    void isAPayBridgeException() {
        CurrencyMismatchException exception = new CurrencyMismatchException(NGN, USD);

        assertThat(exception).isInstanceOf(PayBridgeException.class);
    }

    @Test
    void messageNamesBothCurrencies() {
        CurrencyMismatchException exception = new CurrencyMismatchException(NGN, USD);

        assertThat(exception.getMessage()).isEqualTo("Currency mismatch: NGN vs USD");
    }

    @Test
    void moneyRaisesItWithTheRightCodeWhenCurrenciesClash() {
        Money naira = Money.ngn(50_000);
        Money dollars = Money.ofMinor(500, USD);

        assertThatThrownBy(() -> naira.add(dollars))
                .isInstanceOfSatisfying(CurrencyMismatchException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.CURRENCY_MISMATCH));
    }
}