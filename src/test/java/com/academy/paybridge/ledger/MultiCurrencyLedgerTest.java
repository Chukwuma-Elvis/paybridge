package com.academy.paybridge.ledger;

import com.academy.paybridge.AbstractIntegrationTest;
import com.academy.paybridge.LedgerInvariants;
import com.academy.paybridge.account.api.AccountApi;
import com.academy.paybridge.account.api.AccountSummary;
import com.academy.paybridge.account.api.SystemAccount;
import com.academy.paybridge.ledger.api.EntryType;
import com.academy.paybridge.ledger.api.LedgerApi;
import com.academy.paybridge.ledger.api.PostingLine;
import com.academy.paybridge.ledger.api.PostingRequest;
import com.academy.paybridge.shared.exception.CurrencyMismatchException;
import com.academy.paybridge.shared.exception.ErrorCode;
import com.academy.paybridge.shared.exception.PayBridgeException;
import com.academy.paybridge.shared.exception.ResourceNotFoundException;
import com.academy.paybridge.shared.money.Money;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Step 14: the system is safe for more than one currency. Naira and dollars are separate closed books: each sums to zero on its own,
 * an entry may never mix them, and the new system accounts each hold exactly one currency.
 */
class MultiCurrencyLedgerTest extends AbstractIntegrationTest {

    private static final Currency USD = Currency.getInstance("USD");

    @Autowired LedgerApi ledgerApi;
    @Autowired AccountApi accountApi;
    @Autowired JdbcTemplate jdbc;

    @AfterEach
    void everyCurrencyStillSumsToZero() {
        LedgerInvariants.everyCurrencySumsToZero(jdbc);
    }

    private static String ref() {
        return "mc-" + UUID.randomUUID();
    }

    private UUID system(SystemAccount account) {
        return accountApi.systemAccountId(account);
    }

    private long balance(UUID accountId) {
        return accountApi.getAccount(accountId).balanceMinor();
    }

    // ---------------------------------------------------------------- the new system accounts

    @Test
    void theNewSystemAccountsHoldTheRightCurrency() {
        assertThat(accountApi.getAccount(system(SystemAccount.FX_POSITION_USD)).currency()).isEqualTo(USD);
        assertThat(accountApi.getAccount(system(SystemAccount.REMITTANCE_CLEARING_USD)).currency()).isEqualTo(USD);
        assertThat(accountApi.getAccount(system(SystemAccount.SETTLEMENT_ADJUSTMENT_USD)).currency()).isEqualTo(USD);
        assertThat(accountApi.getAccount(system(SystemAccount.FX_POSITION_NGN)).currency()).isEqualTo(Money.NGN);
        assertThat(accountApi.getAccount(system(SystemAccount.REMITTANCE_CLEARING_NGN)).currency()).isEqualTo(Money.NGN);
        assertThat(accountApi.getAccount(system(SystemAccount.SETTLEMENT_ADJUSTMENT_NGN)).currency()).isEqualTo(Money.NGN);
    }

    @Test
    void theOriginalSystemAccountsAreStillNaira() {
        for (SystemAccount account : Set.of(SystemAccount.FUNDING_SOURCE, SystemAccount.SUSPENSE,
                SystemAccount.PAYSTACK_SETTLEMENT, SystemAccount.FEES)) {
            assertThat(accountApi.getAccount(system(account)).currency()).isEqualTo(Money.NGN);
        }
    }

    // ---------------------------------------------------------------- dollars are their own closed book

    @Test
    void aDollarEntryBetweenTwoDollarAccountsBalancesAndMovesTheMoney() {
        UUID position = system(SystemAccount.FX_POSITION_USD);
        UUID clearing = system(SystemAccount.REMITTANCE_CLEARING_USD);
        long positionBefore = balance(position);
        long clearingBefore = balance(clearing);

        ledgerApi.post(PostingRequest.of(EntryType.FX_CONVERSION_USD, ref(), "dollar test",
                new PostingLine(clearing, Money.ofMinor(-12_345, USD)),
                new PostingLine(position, Money.ofMinor(12_345, USD))));

        assertThat(balance(position)).isEqualTo(positionBefore + 12_345);
        assertThat(balance(clearing)).isEqualTo(clearingBefore - 12_345);
        assertThat(LedgerInvariants.totalFor(jdbc, "USD")).isZero();
    }

    @Test
    void anEntryCannotMixNairaAndDollars() {
        UUID usdAccount = system(SystemAccount.FX_POSITION_USD);
        UUID ngnAccount = system(SystemAccount.FX_POSITION_NGN);

        assertThatThrownBy(() -> ledgerApi.post(PostingRequest.of(EntryType.FX_CONVERSION_USD, ref(), "mixed",
                new PostingLine(usdAccount, Money.ofMinor(-100, USD)),
                new PostingLine(ngnAccount, Money.ngn(100)))))
                .isInstanceOf(CurrencyMismatchException.class);
    }

    @Test
    void dollarsCannotBePostedToANairaAccount() {
        UUID ngnAccount = system(SystemAccount.FX_POSITION_NGN);
        UUID otherNgn = system(SystemAccount.REMITTANCE_CLEARING_NGN);

        assertThatThrownBy(() -> ledgerApi.post(PostingRequest.of(EntryType.FX_CONVERSION_USD, ref(), "wrong book",
                new PostingLine(ngnAccount, Money.ofMinor(-100, USD)),
                new PostingLine(otherNgn, Money.ofMinor(100, USD)))))
                .isInstanceOf(CurrencyMismatchException.class);
    }

    @Test
    void anUnbalancedDollarEntryIsRefused() {
        UUID position = system(SystemAccount.FX_POSITION_USD);
        UUID clearing = system(SystemAccount.REMITTANCE_CLEARING_USD);

        assertThatThrownBy(() -> ledgerApi.post(PostingRequest.of(EntryType.FX_CONVERSION_USD, ref(), "unbalanced",
                new PostingLine(clearing, Money.ofMinor(-100, USD)),
                new PostingLine(position, Money.ofMinor(99, USD)))))
                .isInstanceOf(PayBridgeException.class);
    }

    @Test
    void nairaAndDollarActivityInTheSameTestEachSumToZeroOnTheirOwn() {
        ledgerApi.post(PostingRequest.of(EntryType.FX_CONVERSION_USD, ref(), "usd leg",
                new PostingLine(system(SystemAccount.REMITTANCE_CLEARING_USD), Money.ofMinor(-5_000, USD)),
                new PostingLine(system(SystemAccount.FX_POSITION_USD), Money.ofMinor(5_000, USD))));
        ledgerApi.post(PostingRequest.of(EntryType.FX_CONVERSION_NGN, ref(), "ngn leg",
                new PostingLine(system(SystemAccount.FX_POSITION_NGN), Money.ngn(-7_500_000)),
                new PostingLine(system(SystemAccount.REMITTANCE_CLEARING_NGN), Money.ngn(7_500_000))));

        assertThat(LedgerInvariants.totalFor(jdbc, "USD")).isZero();
        assertThat(LedgerInvariants.totalFor(jdbc, "NGN")).isZero();
    }

    @Test
    void aReversalOfADollarEntryReturnsBothAccountsToWhereTheyWere() {
        UUID position = system(SystemAccount.FX_POSITION_USD);
        UUID clearing = system(SystemAccount.REMITTANCE_CLEARING_USD);
        long positionBefore = balance(position);
        String reference = ref();
        var entry = ledgerApi.post(PostingRequest.of(EntryType.FX_CONVERSION_USD, reference, "to be undone",
                new PostingLine(clearing, Money.ofMinor(-250, USD)),
                new PostingLine(position, Money.ofMinor(250, USD))));

        ledgerApi.reverse(entry.id(), "undo:" + reference, "undo");

        assertThat(balance(position)).isEqualTo(positionBefore);
    }

    @Test
    void theNewEntryTypesAreAcceptedByTheDatabase() {
        UUID position = system(SystemAccount.FX_POSITION_USD);
        UUID clearing = system(SystemAccount.REMITTANCE_CLEARING_USD);
        for (EntryType type : List.of(EntryType.REMITTANCE_FUNDING, EntryType.FX_CONVERSION_USD, EntryType.SETTLEMENT_PAYMENT,
                EntryType.SETTLEMENT_ADJUSTMENT)) {
            ledgerApi.post(PostingRequest.of(type, ref(), "type check",
                    new PostingLine(clearing, Money.ofMinor(-1, USD)),
                    new PostingLine(position, Money.ofMinor(1, USD))));
        }
        UUID nairaOne = system(SystemAccount.FX_POSITION_NGN);
        UUID nairaTwo = system(SystemAccount.REMITTANCE_CLEARING_NGN);
        for (EntryType type : List.of(EntryType.FX_CONVERSION_NGN, EntryType.REMITTANCE_PAYOUT)) {
            ledgerApi.post(PostingRequest.of(type, ref(), "type check",
                    new PostingLine(nairaOne, Money.ngn(-1)), new PostingLine(nairaTwo, Money.ngn(1))));
        }
    }

    @Test
    void dollarPostingsUnderLoadNeverBreakTheBooks() throws Exception {
        UUID position = system(SystemAccount.FX_POSITION_USD);
        UUID clearing = system(SystemAccount.REMITTANCE_CLEARING_USD);
        int threads = 24;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch startingGun = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            final boolean forwards = i % 2 == 0;   // half go one way, half the other: the classic deadlock shape
            Callable<Boolean> task = () -> {
                startingGun.await();
                UUID from = forwards ? clearing : position;
                UUID to = forwards ? position : clearing;
                ledgerApi.post(PostingRequest.of(EntryType.FX_CONVERSION_USD, ref(), "load",
                        new PostingLine(from, Money.ofMinor(-100, USD)), new PostingLine(to, Money.ofMinor(100, USD))));
                return true;
            };
            results.add(pool.submit(task));
        }
        startingGun.countDown();
        for (Future<Boolean> result : results) {
            assertThat(result.get(30, TimeUnit.SECONDS)).isTrue();
        }
        pool.shutdown();

        assertThat(LedgerInvariants.totalFor(jdbc, "USD")).isZero();
    }

    // ---------------------------------------------------------------- accounts created at run time (a partner's, for example)

    @Test
    void aSystemAccountCanBeCreatedOnDemandInTheRightCurrency() {
        String code = "PARTNER_SETTLEMENT_USD:t" + System.nanoTime();

        UUID id = accountApi.ensureSystemAccount(code, "USD");

        AccountSummary summary = accountApi.getAccount(id);
        assertThat(summary.code()).isEqualTo(code);
        assertThat(summary.currency()).isEqualTo(USD);
        assertThat(summary.isCustomerAccount()).isFalse();
        assertThat(summary.accountNumber()).isNull();
        assertThat(summary.balanceMinor()).isZero();
    }

    @Test
    void askingForTheSameSystemAccountAgainGivesTheSameAccount() {
        String code = "PARTNER_SETTLEMENT_USD:t" + System.nanoTime();

        UUID first = accountApi.ensureSystemAccount(code, "USD");
        UUID second = accountApi.ensureSystemAccount(code, "USD");

        assertThat(second).isEqualTo(first);
        assertThat(accountApi.systemAccountIdByCode(code)).isEqualTo(first);
    }

    @Test
    void aSystemAccountIsNeverReusedForAnotherCurrency() {
        String code = "PARTNER_SETTLEMENT_USD:t" + System.nanoTime();
        accountApi.ensureSystemAccount(code, "USD");

        assertThatThrownBy(() -> accountApi.ensureSystemAccount(code, "NGN"))
                .isInstanceOf(PayBridgeException.class)
                .extracting(e -> ((PayBridgeException) e).getErrorCode()).isEqualTo(ErrorCode.CONFLICT);
    }

    @Test
    void anUnsupportedCurrencyIsRefused() {
        assertThatThrownBy(() -> accountApi.ensureSystemAccount("PARTNER_X:t" + System.nanoTime(), "EUR"))
                .isInstanceOf(PayBridgeException.class);
    }

    @Test
    void aSystemAccountCodeThatIsTooLongOrOddlyShapedIsRefusedCleanly() {
        for (String bad : List.of("", "X".repeat(41), "has space", "semi;colon", "quote'", "new\nline")) {
            assertThatThrownBy(() -> accountApi.ensureSystemAccount(bad, "USD"))
                    .as("code [%s]", bad)
                    .isInstanceOf(PayBridgeException.class)
                    .extracting(e -> ((PayBridgeException) e).getErrorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
        }
        assertThatThrownBy(() -> accountApi.ensureSystemAccount(null, "USD")).isInstanceOf(PayBridgeException.class);
    }

    @Test
    void aCodeOfExactlyFortyCharactersIsAccepted() {
        String code = "PARTNER_SETTLEMENT_USD:" + String.valueOf(System.nanoTime()).repeat(2);
        String fitted = code.substring(0, 40);

        assertThat(accountApi.getAccount(accountApi.ensureSystemAccount(fitted, "USD")).code()).isEqualTo(fitted);
    }

    @Test
    void anUnknownSystemAccountCodeIsNotFound() {
        assertThatThrownBy(() -> accountApi.systemAccountIdByCode("NO_SUCH_ACCOUNT"))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void manyCallersCreatingTheSameSystemAccountAtOnceGetOneAccount() throws Exception {
        String code = "PARTNER_USD:race" + System.nanoTime();
        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch startingGun = new CountDownLatch(1);
        List<Future<UUID>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                startingGun.await();
                return accountApi.ensureSystemAccount(code, "USD");
            }));
        }
        startingGun.countDown();
        Set<UUID> ids = new java.util.HashSet<>();
        for (Future<UUID> future : futures) {
            ids.add(future.get(30, TimeUnit.SECONDS));
        }
        pool.shutdown();

        assertThat(ids).hasSize(1);
        Integer rows = jdbc.queryForObject("select count(*) from account where code = ?", Integer.class, code);
        assertThat(rows).isEqualTo(1);
        assertThat(ids.stream().map(UUID::toString).collect(Collectors.toSet())).isNotEmpty();
    }
}
