package com.academy.paybridge.shared.money;

import com.academy.paybridge.shared.exception.CurrencyMismatchException;

import java.util.Currency;
import java.util.Objects;

public record Money(long minorUnits, Currency currency) implements Comparable<Money> {

    public static final Currency NGN = Currency.getInstance("NGN");

    public Money {
        Objects.requireNonNull(currency, "currency must not be null");
    }

    public static Money ofMinor(long minorUnits, Currency currency) {
        return new Money(minorUnits, currency);
    }

    public static Money ngn(long kobo) {
        return new Money(kobo, NGN);
    }

    public static Money zero(Currency currency) {
        return new Money(0, currency);
    }

    public Money add(Money other) {
        requireSameCurrency(other);
        return new Money(Math.addExact(minorUnits, other.minorUnits), currency);
    }


    public Money subtract(Money other) {
        requireSameCurrency(other);
        return new Money(Math.subtractExact(minorUnits, other.minorUnits), currency);
    }

    public Money negate() {
        return new Money(Math.negateExact(minorUnits), currency);
    }

    public boolean isPositive() {
        return minorUnits > 0;
    }

    public boolean isNegative() {
        return minorUnits < 0;
    }

    public boolean isZero() {
        return minorUnits == 0;
    }

    @Override
    public int compareTo(Money other) {
        requireSameCurrency(other);
        return Long.compare(minorUnits, other.minorUnits);
    }


    private void requireSameCurrency(Money other) {
        if (!currency.equals(other.currency)) {
            throw new CurrencyMismatchException(currency, other.currency);
        }
    }


}
