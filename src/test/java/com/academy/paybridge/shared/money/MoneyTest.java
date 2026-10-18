package com.academy.paybridge.shared.money;

import com.academy.paybridge.shared.exception.CurrencyMismatchException;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Currency;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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

    // ---------------------------------------------------------------- negate

    @Test
    void negatingAPositiveAmountGivesTheSameAmountBelowZero() {
        assertThat(Money.ngn(500).negate()).isEqualTo(Money.ngn(-500));
    }

    @Test
    void negatingANegativeAmountGivesThePositive() {
        assertThat(Money.ngn(-500).negate()).isEqualTo(Money.ngn(500));
    }

    @Test
    void negatingZeroGivesZero() {
        assertThat(Money.ngn(0).negate()).isEqualTo(Money.ngn(0));
    }

    @Test
    void negatingTwiceGivesTheOriginal() {
        Money original = Money.ngn(12_345);

        assertThat(original.negate().negate()).isEqualTo(original);
    }

    @Test
    void negatingKeepsTheCurrency() {
        Money dollars = Money.ofMinor(700, USD);

        assertThat(dollars.negate().currency()).isEqualTo(USD);
    }

    @Test
    void anAmountPlusItsNegationIsZero() {
        Money amount = Money.ngn(98_765);

        assertThat(amount.add(amount.negate()).isZero()).isTrue();
    }

    // ---------------------------------------------------------------- sign checks

    @Test
    void aPositiveAmountIsPositiveOnly() {
        Money money = Money.ngn(1);

        assertThat(money.isPositive()).isTrue();
        assertThat(money.isNegative()).isFalse();
        assertThat(money.isZero()).isFalse();
    }

    @Test
    void aNegativeAmountIsNegativeOnly() {
        Money money = Money.ngn(-1);

        assertThat(money.isPositive()).isFalse();
        assertThat(money.isNegative()).isTrue();
        assertThat(money.isZero()).isFalse();
    }

    @Test
    void zeroIsNeitherPositiveNorNegative() {
        Money money = Money.ngn(0);

        assertThat(money.isPositive()).isFalse();
        assertThat(money.isNegative()).isFalse();
        assertThat(money.isZero()).isTrue();
    }

    @Test
    void theSignChecksWorkAtTheExtremes() {
        assertThat(Money.ngn(Long.MAX_VALUE).isPositive()).isTrue();
        assertThat(Money.ngn(Long.MIN_VALUE).isNegative()).isTrue();
    }

    // ---------------------------------------------------------------- equality and hashCode

    @Test
    void sameAmountAndCurrencyAreEqual() {
        assertThat(Money.ngn(500)).isEqualTo(Money.ngn(500));
        assertThat(Money.ngn(500)).isEqualTo(Money.ofMinor(500, Money.NGN));
    }

    @Test
    void differentAmountsAreNotEqual() {
        assertThat(Money.ngn(500)).isNotEqualTo(Money.ngn(501));
    }

    @Test
    void sameAmountInDifferentCurrenciesIsNotEqual() {
        assertThat(Money.ngn(500)).isNotEqualTo(Money.ofMinor(500, USD));
    }

    @Test
    void equalAmountsHaveTheSameHashCode() {
        assertThat(Money.ngn(500).hashCode()).isEqualTo(Money.ngn(500).hashCode());
    }

    @Test
    void anAmountIsNeverEqualToNullOrToSomethingElse() {
        assertThat(Money.ngn(500)).isNotEqualTo(null);
        assertThat(Money.ngn(500)).isNotEqualTo(500L);
    }

    @Test
    void zeroIsTheSameWhicheverWayItIsMade() {
        assertThat(Money.zero(Money.NGN)).isEqualTo(Money.ngn(0));
    }

    @Test
    void equalAmountsCollapseInASet() {
        Set<Money> set = new HashSet<>(List.of(Money.ngn(500), Money.ngn(500), Money.ngn(501), Money.ofMinor(500, USD)));

        assertThat(set).hasSize(3);
    }

    // ---------------------------------------------------------------- compareTo

    @Test
    void aSmallerAmountComesFirst() {
        assertThat(Money.ngn(100).compareTo(Money.ngn(200))).isNegative();
    }

    @Test
    void aLargerAmountComesLater() {
        assertThat(Money.ngn(200).compareTo(Money.ngn(100))).isPositive();
    }

    @Test
    void equalAmountsCompareAsZero() {
        assertThat(Money.ngn(200).compareTo(Money.ngn(200))).isZero();
    }

    @Test
    void negativeAmountsOrderBelowZeroAndBelowPositives() {
        assertThat(Money.ngn(-5).compareTo(Money.ngn(0))).isNegative();
        assertThat(Money.ngn(-5).compareTo(Money.ngn(5))).isNegative();
        assertThat(Money.ngn(-5).compareTo(Money.ngn(-10))).isPositive();
    }

    @Test
    void comparingDoesNotOverflowAtTheExtremes() {
        assertThat(Money.ngn(Long.MIN_VALUE).compareTo(Money.ngn(Long.MAX_VALUE))).isNegative();
        assertThat(Money.ngn(Long.MAX_VALUE).compareTo(Money.ngn(Long.MIN_VALUE))).isPositive();
    }

    @Test
    void aListOfAmountsSortsFromLowestToHighest() {
        List<Money> sorted = new ArrayList<>(List.of(Money.ngn(300), Money.ngn(-100), Money.ngn(0), Money.ngn(200)));

        Collections.sort(sorted);

        assertThat(sorted).containsExactly(Money.ngn(-100), Money.ngn(0), Money.ngn(200), Money.ngn(300));
    }

    // ---------------------------------------------------------------- currency mismatch

    @Test
    void refusesToSubtractDifferentCurrencies() {
        Money naira = Money.ngn(50_000);
        Money dollars = Money.ofMinor(500, USD);

        assertThatThrownBy(() -> naira.subtract(dollars))
                .isInstanceOf(CurrencyMismatchException.class);
    }

    @Test
    void refusesToCompareDifferentCurrencies() {
        Money naira = Money.ngn(50_000);
        Money dollars = Money.ofMinor(500, USD);

        assertThatThrownBy(() -> naira.compareTo(dollars))
                .isInstanceOf(CurrencyMismatchException.class);
    }

    @Test
    void theMismatchErrorNamesBothCurrencies() {
        assertThatThrownBy(() -> Money.ngn(1).subtract(Money.ofMinor(1, USD)))
                .hasMessageContaining("NGN")
                .hasMessageContaining("USD");
    }

    @Test
    void aMismatchIsRefusedEvenWhenBothAmountsAreZero() {
        assertThatThrownBy(() -> Money.zero(Money.NGN).add(Money.zero(USD)))
                .isInstanceOf(CurrencyMismatchException.class);
    }

    // ---------------------------------------------------------------- immutability

    @Test
    void addingLeavesBothOriginalsUnchanged() {
        Money first = Money.ngn(100);
        Money second = Money.ngn(40);

        Money result = first.add(second);

        assertThat(result).isEqualTo(Money.ngn(140));
        assertThat(first).isEqualTo(Money.ngn(100));
        assertThat(second).isEqualTo(Money.ngn(40));
    }

    @Test
    void subtractingLeavesBothOriginalsUnchanged() {
        Money first = Money.ngn(100);
        Money second = Money.ngn(40);

        Money result = first.subtract(second);

        assertThat(result).isEqualTo(Money.ngn(60));
        assertThat(first).isEqualTo(Money.ngn(100));
        assertThat(second).isEqualTo(Money.ngn(40));
    }

    @Test
    void negatingLeavesTheOriginalUnchanged() {
        Money original = Money.ngn(100);

        Money negated = original.negate();

        assertThat(negated).isEqualTo(Money.ngn(-100));
        assertThat(original).isEqualTo(Money.ngn(100));
    }

    @Test
    void everyOperationGivesABrandNewObject() {
        Money original = Money.ngn(100);

        assertThat(original.add(Money.ngn(0))).isNotSameAs(original);
        assertThat(original.subtract(Money.ngn(0))).isNotSameAs(original);
        assertThat(original.negate()).isNotSameAs(original);
    }

    @Test
    void aFailedOperationLeavesTheOriginalsUnchanged() {
        Money naira = Money.ngn(100);
        Money dollars = Money.ofMinor(100, USD);

        assertThatThrownBy(() -> naira.add(dollars)).isInstanceOf(CurrencyMismatchException.class);

        assertThat(naira).isEqualTo(Money.ngn(100));
        assertThat(dollars).isEqualTo(Money.ofMinor(100, USD));
    }

    @Test
    void theRecordHasNoWayToChangeItsAmount() {
        // a record's components are final: there is no setter and no way to reassign the amount
        assertThat(Money.class.isRecord()).isTrue();
        assertThat(java.util.Arrays.stream(Money.class.getDeclaredMethods()).map(java.lang.reflect.Method::getName))
                .noneMatch(name -> name.startsWith("set"));
    }

    // ---------------------------------------------------------------- factories

    @Test
    void ngnAndOfMinorMakeTheSameThing() {
        assertThat(Money.ngn(250)).isEqualTo(Money.ofMinor(250, Currency.getInstance("NGN")));
    }

    @Test
    void theNairaConstantIsNgn() {
        assertThat(Money.NGN.getCurrencyCode()).isEqualTo("NGN");
    }

    @Test
    void anAmountReportsItsCurrencyAndMinorUnits() {
        Money money = Money.ofMinor(1_234, USD);

        assertThat(money.minorUnits()).isEqualTo(1_234);
        assertThat(money.currency()).isEqualTo(USD);
    }

    private static final Currency USD = Currency.getInstance("USD");
}
