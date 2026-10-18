package com.academy.paybridge.fx;

import com.academy.paybridge.AbstractIntegrationTest;
import com.academy.paybridge.LedgerInvariants;
import com.academy.paybridge.account.api.AccountApi;
import com.academy.paybridge.account.api.SystemAccount;
import com.academy.paybridge.fx.api.Conversion;
import com.academy.paybridge.fx.api.Corridor;
import com.academy.paybridge.fx.api.FxApi;
import com.academy.paybridge.fx.api.Quote;
import com.academy.paybridge.fx.client.FakeRateProvider;
import com.academy.paybridge.fx.service.FxRateService;
import com.academy.paybridge.ledger.api.EntryType;
import com.academy.paybridge.ledger.api.LedgerApi;
import com.academy.paybridge.shared.exception.ErrorCode;
import com.academy.paybridge.shared.exception.PayBridgeException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Step 18: a conversion is two linked ledger entries, atomic, reversible and idempotent. */
class FxConversionTest extends AbstractIntegrationTest {

    @Autowired FxApi fxApi;
    @Autowired FxRateService rates;
    @Autowired FakeRateProvider source;
    @Autowired AccountApi accountApi;
    @Autowired LedgerApi ledger;
    @Autowired JdbcTemplate jdbc;

    private UUID clearingUsd;
    private UUID clearingNgn;

    @BeforeEach
    void aKnownRateAndFreshClearingAccounts() {
        source.reset();
        rates.setManually(Corridor.USD_NGN, new BigDecimal("1500"), "baseline", "test");
        rates.setSpread(Corridor.USD_NGN, 100, "test");
        clearingUsd = accountApi.ensureSystemAccount("T-CLR-USD-" + UUID.randomUUID().toString().substring(0, 8), "USD");
        clearingNgn = accountApi.ensureSystemAccount("T-CLR-NGN-" + UUID.randomUUID().toString().substring(0, 8), "NGN");
    }

    @AfterEach
    void everyCurrencyStillSumsToZero() {
        LedgerInvariants.everyCurrencySumsToZero(jdbc);
    }

    private Quote consumedQuote(long sell) {
        Quote quote = fxApi.createQuote(Corridor.USD_NGN, sell, "caller-" + UUID.randomUUID(), "key-" + UUID.randomUUID()).quote();
        fxApi.consumeQuote(quote.id(), UUID.randomUUID());
        return quote;
    }

    private long balance(UUID accountId) {
        return accountApi.getAccount(accountId).balanceMinor();
    }

    private long balance(SystemAccount account) {
        return balance(accountApi.systemAccountId(account));
    }

    private long feesUsd() {
        return balance(accountApi.ensureSystemAccount("FEES_USD", "USD"));
    }

    // ---- posting

    @Test
    void bothLegsPostAndEachCurrencyBalances() {
        Quote quote = consumedQuote(100_000);
        long positionUsd = balance(SystemAccount.FX_POSITION_USD);
        long positionNgn = balance(SystemAccount.FX_POSITION_NGN);
        long fees = feesUsd();

        Conversion conversion = fxApi.convert(quote.id(), clearingUsd, clearingNgn);

        assertThat(conversion.sellMinor()).isEqualTo(100_000);
        assertThat(conversion.feeMinor()).isEqualTo(500);
        assertThat(conversion.buyMinor()).isEqualTo(147_757_500L);
        assertThat(balance(clearingUsd)).isEqualTo(-100_000);                                   // dollars out of clearing
        assertThat(balance(SystemAccount.FX_POSITION_USD) - positionUsd).isEqualTo(99_500);     // the house is long dollars
        assertThat(feesUsd() - fees).isEqualTo(500);                                            // our fee, kept in dollars
        assertThat(balance(SystemAccount.FX_POSITION_NGN) - positionNgn).isEqualTo(-147_757_500L); // and short naira
        assertThat(balance(clearingNgn)).isEqualTo(147_757_500L);                               // naira into clearing
        assertThat(ledger.find(EntryType.FX_CONVERSION_USD, "CONV-" + quote.reference())).isPresent();
        assertThat(ledger.find(EntryType.FX_CONVERSION_NGN, "CONV-" + quote.reference())).isPresent();
    }

    @Test
    void theLegsAreLinkedAndFindableByTheQuote() {
        Quote quote = consumedQuote(50_000);

        Conversion conversion = fxApi.convert(quote.id(), clearingUsd, clearingNgn);

        assertThat(fxApi.findConversion(quote.id())).hasValueSatisfying(found -> {
            assertThat(found.id()).isEqualTo(conversion.id());
            assertThat(found.usdEntryId()).isNotEqualTo(found.ngnEntryId());
            assertThat(found.reversed()).isFalse();
        });
    }

    @Test
    void replayingTheSameConversionChangesNothing() {
        Quote quote = consumedQuote(100_000);
        Conversion first = fxApi.convert(quote.id(), clearingUsd, clearingNgn);
        long usdBefore = balance(clearingUsd);
        long ngnBefore = balance(clearingNgn);

        Conversion second = fxApi.convert(quote.id(), clearingUsd, clearingNgn);

        assertThat(second.id()).isEqualTo(first.id());
        assertThat(balance(clearingUsd)).isEqualTo(usdBefore);
        assertThat(balance(clearingNgn)).isEqualTo(ngnBefore);
    }

    @Test
    void aQuoteThatWasNeverUsedCannotBeConverted() {
        Quote open = fxApi.createQuote(Corridor.USD_NGN, 100_000, "caller-" + UUID.randomUUID(), "key-" + UUID.randomUUID()).quote();

        assertThatThrownBy(() -> fxApi.convert(open.id(), clearingUsd, clearingNgn))
                .isInstanceOf(PayBridgeException.class)
                .extracting(e -> ((PayBridgeException) e).getErrorCode()).isEqualTo(ErrorCode.INVALID_STATE);
    }

    @Test
    void accountsInTheWrongCurrencyAreRefused() {
        Quote quote = consumedQuote(100_000);

        assertThatThrownBy(() -> fxApi.convert(quote.id(), clearingNgn, clearingUsd))
                .isInstanceOf(PayBridgeException.class)
                .extracting(e -> ((PayBridgeException) e).getErrorCode()).isEqualTo(ErrorCode.CURRENCY_MISMATCH);
        assertThat(fxApi.findConversion(quote.id())).isEmpty();
    }

    @Test
    void whenTheSecondLegFailsTheFirstIsRolledBack() {
        Quote quote = consumedQuote(100_000);
        long usdBefore = balance(clearingUsd);
        jdbc.update("update account set status = 'FROZEN' where id = ?", clearingNgn);

        assertThatThrownBy(() -> fxApi.convert(quote.id(), clearingUsd, clearingNgn)).isInstanceOf(PayBridgeException.class);

        assertThat(balance(clearingUsd)).as("the dollar leg did not survive").isEqualTo(usdBefore);
        assertThat(ledger.find(EntryType.FX_CONVERSION_USD, "CONV-" + quote.reference())).isEmpty();
        assertThat(fxApi.findConversion(quote.id())).isEmpty();
    }

    // ---- reversing

    @Test
    void reversingReturnsBothPositionsExactly() {
        Quote quote = consumedQuote(123_457);
        long positionUsd = balance(SystemAccount.FX_POSITION_USD);
        long positionNgn = balance(SystemAccount.FX_POSITION_NGN);
        long fees = feesUsd();
        fxApi.convert(quote.id(), clearingUsd, clearingNgn);

        Conversion reversed = fxApi.reverseConversion(quote.id(), "payout failed");

        assertThat(reversed.reversed()).isTrue();
        assertThat(balance(clearingUsd)).isZero();
        assertThat(balance(clearingNgn)).isZero();
        assertThat(balance(SystemAccount.FX_POSITION_USD)).isEqualTo(positionUsd);
        assertThat(balance(SystemAccount.FX_POSITION_NGN)).isEqualTo(positionNgn);
        assertThat(feesUsd()).isEqualTo(fees);
    }

    @Test
    void reversingTwiceChangesNothingTheSecondTime() {
        Quote quote = consumedQuote(100_000);
        fxApi.convert(quote.id(), clearingUsd, clearingNgn);
        fxApi.reverseConversion(quote.id(), "first");
        long usd = balance(clearingUsd);

        Conversion again = fxApi.reverseConversion(quote.id(), "second");

        assertThat(again.reversed()).isTrue();
        assertThat(balance(clearingUsd)).isEqualTo(usd);
    }

    @Test
    void aConversionThatNeverHappenedCannotBeReversed() {
        Quote quote = consumedQuote(100_000);

        assertThatThrownBy(() -> fxApi.reverseConversion(quote.id(), "x")).hasMessageContaining("not found");
    }

    // ---- concurrency

    @Test
    void manyConversionsAtOnceOnTheSameAccountsDoNotDeadlockOrLoseMoney() throws Exception {
        int count = 8;
        List<Quote> quotes = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            quotes.add(consumedQuote(100_000 + i * 1_000L));
        }
        long expectedNgn = quotes.stream().mapToLong(Quote::buyMinor).sum();
        long expectedUsd = quotes.stream().mapToLong(Quote::sellMinor).sum();
        ExecutorService pool = Executors.newFixedThreadPool(count);
        CountDownLatch gun = new CountDownLatch(1);
        List<Future<Conversion>> results = new ArrayList<>();
        for (Quote quote : quotes) {
            results.add(pool.submit(() -> {
                gun.await();
                return fxApi.convert(quote.id(), clearingUsd, clearingNgn);
            }));
        }
        gun.countDown();
        for (Future<Conversion> f : results) {
            f.get(30, java.util.concurrent.TimeUnit.SECONDS);
        }
        pool.shutdown();

        assertThat(balance(clearingUsd)).isEqualTo(-expectedUsd);
        assertThat(balance(clearingNgn)).isEqualTo(expectedNgn);
    }
}
