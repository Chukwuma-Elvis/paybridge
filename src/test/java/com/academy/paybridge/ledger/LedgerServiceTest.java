package com.academy.paybridge.ledger;

import com.academy.paybridge.LedgerInvariants;
import com.academy.paybridge.AbstractIntegrationTest;
import com.academy.paybridge.account.api.AccountApi;
import com.academy.paybridge.account.api.InsufficientFundsException;
import com.academy.paybridge.account.api.SystemAccount;
import com.academy.paybridge.ledger.api.EntryType;
import com.academy.paybridge.ledger.api.LedgerApi;
import com.academy.paybridge.ledger.api.PostedEntry;
import com.academy.paybridge.ledger.api.PostingLine;
import com.academy.paybridge.ledger.api.PostingRequest;
import com.academy.paybridge.shared.exception.CurrencyMismatchException;
import com.academy.paybridge.shared.exception.ErrorCode;
import com.academy.paybridge.shared.exception.PayBridgeException;
import com.academy.paybridge.shared.money.Money;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Currency;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LedgerServiceTest extends AbstractIntegrationTest {

    @Autowired
    LedgerApi ledgerApi;

    @Autowired
    AccountApi accountApi;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    TransactionTemplate transactionTemplate;

    private static String ref() {
        return "test-" + UUID.randomUUID();
    }

    private long balance(UUID accountId) {
        return accountApi.getAccount(accountId).balanceMinor();
    }

    private PostedEntry transfer(UUID from, UUID to, long kobo, String reference) {
        return ledgerApi.post(PostingRequest.of(EntryType.TRANSFER, reference, "test transfer",
                new PostingLine(from, Money.ngn(-kobo)),
                new PostingLine(to, Money.ngn(kobo))));
    }

    // ---------- the happy path ----------

    @Test
    void fundingMovesMoneyOutOfTheFundingSourceIntoTheAccount() throws Exception {
        UUID funding = accountApi.systemAccountId(SystemAccount.FUNDING_SOURCE);
        long fundingBefore = balance(funding);
        UUID account = newAccount();

        fundAccount(account, 10_000);

        assertThat(balance(account)).isEqualTo(10_000);
        assertThat(balance(funding)).isEqualTo(fundingBefore - 10_000);
        assertThat(ledgerApi.sumOfLines(account)).isEqualTo(10_000);
    }

    @Test
    void aTransferMovesExactlyTheAmountAndNothingIsCreatedOrDestroyed() throws Exception {
        UUID from = newFundedAccount(10_000);
        UUID to = newAccount();

        transfer(from, to, 3_500, ref());

        assertThat(balance(from)).isEqualTo(6_500);
        assertThat(balance(to)).isEqualTo(3_500);
        assertThat(balance(from) + balance(to)).isEqualTo(10_000);
    }

    @Test
    void theCachedBalanceAlwaysEqualsTheSumOfTheLedgerLines() throws Exception {
        UUID a = newFundedAccount(50_000);
        UUID b = newAccount();
        transfer(a, b, 12_000, ref());
        transfer(b, a, 2_000, ref());
        transfer(a, b, 7_777, ref());

        assertThat(balance(a)).isEqualTo(ledgerApi.sumOfLines(a));
        assertThat(balance(b)).isEqualTo(ledgerApi.sumOfLines(b));
    }

    @Test
    void acrossTheWholeBankAllBalancesAddUpToZero() throws Exception {
        UUID a = newFundedAccount(20_000);
        UUID b = newAccount();
        transfer(a, b, 5_000, ref());

        LedgerInvariants.everyCurrencySumsToZero(jdbcTemplate);
    }

    // ---------- bad requests are refused and change nothing ----------

    @Test
    void refusesAnEntryThatDoesNotBalance() throws Exception {
        UUID from = newFundedAccount(10_000);
        UUID to = newAccount();

        assertThatThrownBy(() -> ledgerApi.post(PostingRequest.of(EntryType.TRANSFER, ref(), "unbalanced",
                new PostingLine(from, Money.ngn(-1_000)),
                new PostingLine(to, Money.ngn(900)))))
                .isInstanceOfSatisfying(PayBridgeException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED))
                .hasMessageContaining("does not balance");

        assertThat(balance(from)).isEqualTo(10_000);
        assertThat(balance(to)).isZero();
    }

    @Test
    void refusesAnEntryWithASingleLine() throws Exception {
        UUID account = newFundedAccount(1_000);

        assertThatThrownBy(() -> ledgerApi.post(PostingRequest.of(EntryType.TRANSFER, ref(), "one line",
                new PostingLine(account, Money.ngn(-1_000)))))
                .isInstanceOf(PayBridgeException.class)
                .hasMessageContaining("at least two lines");
    }

    @Test
    void refusesAZeroAmountLine() throws Exception {
        UUID a = newAccount();
        UUID b = newAccount();

        assertThatThrownBy(() -> ledgerApi.post(PostingRequest.of(EntryType.TRANSFER, ref(), "zero",
                new PostingLine(a, Money.ngn(0)),
                new PostingLine(b, Money.ngn(0)))))
                .isInstanceOf(PayBridgeException.class)
                .hasMessageContaining("zero");
    }

    @Test
    void refusesABlankReference() throws Exception {
        UUID a = newFundedAccount(100);
        UUID b = newAccount();

        assertThatThrownBy(() -> transfer(a, b, 100, " "))
                .isInstanceOf(PayBridgeException.class)
                .hasMessageContaining("reference");
    }

    @Test
    void refusesMixedCurrenciesInOneEntry() throws Exception {
        UUID a = newFundedAccount(1_000);
        UUID b = newAccount();

        assertThatThrownBy(() -> ledgerApi.post(PostingRequest.of(EntryType.TRANSFER, ref(), "mixed",
                new PostingLine(a, Money.ngn(-1_000)),
                new PostingLine(b, Money.ofMinor(1_000, Currency.getInstance("USD"))))))
                .isInstanceOf(CurrencyMismatchException.class);
    }

    @Test
    void anOverdraftIsRefusedAndNothingChangesAnywhere() throws Exception {
        UUID from = newFundedAccount(1_000);
        UUID to = newAccount();
        String reference = ref();

        assertThatThrownBy(() -> transfer(from, to, 1_001, reference))
                .isInstanceOf(InsufficientFundsException.class);

        assertThat(balance(from)).isEqualTo(1_000);
        assertThat(balance(to)).isZero();
        assertThat(ledgerApi.find(EntryType.TRANSFER, reference)).isEmpty();
    }

    @Test
    void aFrozenAccountCannotMoveMoney() throws Exception {
        UUID from = newFundedAccount(1_000);
        UUID to = newAccount();
        jdbcTemplate.update("update account set status = 'FROZEN' where id = ?", from);

        assertThatThrownBy(() -> transfer(from, to, 100, ref()))
                .isInstanceOfSatisfying(PayBridgeException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.ACCOUNT_NOT_ACTIVE));
        assertThat(balance(from)).isEqualTo(1_000);
    }

    // ---------- retry safety ----------

    @Test
    void postingTheSameEventTwiceMovesMoneyOnlyOnce() throws Exception {
        UUID from = newFundedAccount(10_000);
        UUID to = newAccount();
        String reference = ref();

        PostedEntry first = transfer(from, to, 4_000, reference);
        PostedEntry second = transfer(from, to, 4_000, reference);

        assertThat(second.id()).isEqualTo(first.id());
        assertThat(balance(from)).isEqualTo(6_000);
        assertThat(balance(to)).isEqualTo(4_000);
    }

    @Test
    void reusingAReferenceForDifferentLinesIsAConflict() throws Exception {
        UUID from = newFundedAccount(10_000);
        UUID to = newAccount();
        String reference = ref();
        transfer(from, to, 4_000, reference);

        assertThatThrownBy(() -> transfer(from, to, 5_000, reference))
                .isInstanceOfSatisfying(PayBridgeException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.CONFLICT));
        assertThat(balance(from)).isEqualTo(6_000);
    }

    // ---------- reversals ----------

    @Test
    void aReversalPutsEveryBalanceBackAndKeepsTheHistory() throws Exception {
        UUID from = newFundedAccount(10_000);
        UUID to = newAccount();
        PostedEntry original = transfer(from, to, 4_000, ref());

        PostedEntry reversal = ledgerApi.reverse(original.id(), ref(), "undo");

        assertThat(reversal.type()).isEqualTo(EntryType.REVERSAL);
        assertThat(reversal.reversesEntryId()).isEqualTo(original.id());
        assertThat(balance(from)).isEqualTo(10_000);
        assertThat(balance(to)).isZero();
        assertThat(ledgerApi.statement(from, 10)).hasSize(3); // funding, transfer, reversal: nothing was erased
    }

    @Test
    void aReversalRetriedWithTheSameReferenceIsHarmless() throws Exception {
        UUID from = newFundedAccount(10_000);
        UUID to = newAccount();
        PostedEntry original = transfer(from, to, 4_000, ref());
        String reversalRef = ref();

        PostedEntry first = ledgerApi.reverse(original.id(), reversalRef, "undo");
        PostedEntry second = ledgerApi.reverse(original.id(), reversalRef, "undo");

        assertThat(second.id()).isEqualTo(first.id());
        assertThat(balance(from)).isEqualTo(10_000);
    }

    @Test
    void anEntryCanOnlyBeReversedOnce() throws Exception {
        UUID from = newFundedAccount(10_000);
        UUID to = newAccount();
        PostedEntry original = transfer(from, to, 4_000, ref());
        ledgerApi.reverse(original.id(), ref(), "undo");

        assertThatThrownBy(() -> ledgerApi.reverse(original.id(), ref(), "undo again"))
                .isInstanceOfSatisfying(PayBridgeException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.CONFLICT));
        assertThat(balance(from)).isEqualTo(10_000);
    }

    @Test
    void aReversalIsRefusedIfTheMoneyHasAlreadyBeenSpent() throws Exception {
        UUID a = newFundedAccount(1_000);
        UUID b = newAccount();
        UUID c = newAccount();
        PostedEntry original = transfer(a, b, 1_000, ref());
        transfer(b, c, 1_000, ref());

        assertThatThrownBy(() -> ledgerApi.reverse(original.id(), ref(), "undo"))
                .isInstanceOf(InsufficientFundsException.class);
        assertThat(balance(b)).isZero();
        assertThat(balance(c)).isEqualTo(1_000);
    }

    // ---------- the database's own safety nets ----------

    @Test
    void theDatabaseRefusesALedgerEntryThatDoesNotBalanceEvenIfTheCodeIsBypassed() throws Exception {
        UUID a = newAccount();
        UUID b = newAccount();
        UUID entryId = UUID.randomUUID();

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(tx -> {
            jdbcTemplate.update("insert into journal_entry (id, entry_type, reference, created_at) "
                    + "values (?, 'TRANSFER', ?, now())", entryId, ref());
            jdbcTemplate.update("insert into ledger_line (id, entry_id, account_id, amount_minor, currency, created_at) "
                    + "values (?, ?, ?, -100, 'NGN', now())", UUID.randomUUID(), entryId, a);
            jdbcTemplate.update("insert into ledger_line (id, entry_id, account_id, amount_minor, currency, created_at) "
                    + "values (?, ?, ?, 90, 'NGN', now())", UUID.randomUUID(), entryId, b);
        })).hasStackTraceContaining("does not balance");
    }

    @Test
    void theLedgerCannotBeEditedOrDeleted() throws Exception {
        UUID from = newFundedAccount(1_000);
        UUID entryId = ledgerApi.statement(from, 1).get(0).entryId();

        assertThatThrownBy(() -> jdbcTemplate.update(
                "update ledger_line set amount_minor = 5 where entry_id = ?", entryId))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasStackTraceContaining("append-only");
        assertThatThrownBy(() -> jdbcTemplate.update("delete from ledger_line where entry_id = ?", entryId))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasStackTraceContaining("append-only");
        assertThatThrownBy(() -> jdbcTemplate.update("delete from journal_entry where id = ?", entryId))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasStackTraceContaining("append-only");
    }
}
