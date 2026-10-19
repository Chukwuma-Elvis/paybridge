package com.academy.paybridge.transfer.service;

import com.academy.paybridge.account.api.AccountApi;
import com.academy.paybridge.account.api.AccountSummary;
import com.academy.paybridge.account.api.SystemAccount;
import com.academy.paybridge.ledger.api.EntryType;
import com.academy.paybridge.ledger.api.LedgerApi;
import com.academy.paybridge.ledger.api.PostedEntry;
import com.academy.paybridge.ledger.api.PostingLine;
import com.academy.paybridge.ledger.api.PostingRequest;
import com.academy.paybridge.shared.exception.ErrorCode;
import com.academy.paybridge.shared.exception.PayBridgeException;
import com.academy.paybridge.shared.money.Money;
import com.academy.paybridge.transfer.api.PayoutApi;
import com.academy.paybridge.transfer.client.GatewayOutcome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Currency;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Everything that changes the database for a system-initiated payout. Every public method is ONE short transaction, and none of them
 * talks to the payment provider: {@link SystemPayoutDispatcher} and {@link SystemPayoutReconciler} do that, with no transaction open.
 * <p>
 * The money follows the same two steps as an external transfer, through the SUSPENSE account:
 * <pre>
 *   request   source   -amount     suspense  +amount                              (EXTERNAL_HOLD)
 *   success   suspense -amount     paystack  +amount                              (EXTERNAL_SETTLEMENT)
 *   failure   the hold is reversed: the money returns to the source account
 * </pre>
 */
@Service
@Transactional
public class SystemPayoutService implements PayoutApi {

    /** Every system payout reference starts with this, which is how a webhook is routed to the right place. */
    public static final String PREFIX = "PO-";

    private static final Logger log = LoggerFactory.getLogger(SystemPayoutService.class);
    private static final Pattern REFERENCE = Pattern.compile("PO-[A-Za-z0-9_-]{4,37}");
    private static final Currency NGN = Currency.getInstance("NGN");
    private static final Duration MAX_BACKOFF = Duration.ofMinutes(5);

    /** What a webhook is checked against. */
    public record Facts(long amountMinor, String currency, PayoutStatus status) {
    }

    /** What the dispatcher must send. */
    public record Dispatch(String reference, long amountMinor, String currency, String bankCode, String accountNumber,
                           String accountName, String narration) {
    }

    private record Row(String reference, PayoutStatus status, long amountMinor, String currency, String bankCode,
                       String accountNumber, String accountName, String narration, UUID sourceAccountId, String failureReason,
                       int attempts) {
    }

    private static final RowMapper<Row> ROW = (rs, n) -> new Row(rs.getString("reference"),
            PayoutStatus.valueOf(rs.getString("status")), rs.getLong("amount_minor"), rs.getString("currency"),
            rs.getString("bank_code"), rs.getString("account_number"), rs.getString("account_name"), rs.getString("narration"),
            rs.getObject("source_account_id", UUID.class), rs.getString("failure_reason"), rs.getInt("attempts"));

    private final JdbcTemplate jdbc;
    private final LedgerApi ledger;
    private final AccountApi accounts;
    private final Clock clock;
    private final int maxAttempts;
    private final Duration baseDelay;

    public SystemPayoutService(JdbcTemplate jdbc, LedgerApi ledger, AccountApi accounts, Clock clock,
                               @Value("${paybridge.dispatcher.max-attempts:5}") int maxAttempts,
                               @Value("${paybridge.dispatcher.base-delay:2s}") Duration baseDelay) {
        this.jdbc = jdbc;
        this.ledger = ledger;
        this.accounts = accounts;
        this.clock = clock;
        this.maxAttempts = maxAttempts;
        this.baseDelay = baseDelay;
    }

    // ---- step 1: accept the request

    @Override
    public PayoutView request(PayoutRequest request) {
        validate(request);
        int inserted = jdbc.update("""
                insert into system_payout (id, reference, source_account_id, amount_minor, currency, bank_code, account_number,
                                           account_name, narration, status, dispatch_state, attempts, next_attempt_at, created_at, updated_at)
                values (?, ?, ?, ?, 'NGN', ?, ?, ?, ?, 'PENDING', 'PENDING', 0, ?, now(), now())
                on conflict (reference) do nothing
                """, UUID.randomUUID(), request.reference(), request.sourceAccountId(), request.amountMinor(), request.bankCode(),
                request.accountNumber(), request.accountName().trim(), request.narration(), Timestamp.from(clock.instant()));
        Row row = load(request.reference()).orElseThrow();
        if (inserted == 0) {
            if (row.amountMinor() != request.amountMinor() || !row.accountNumber().equals(request.accountNumber())
                    || !row.bankCode().equals(request.bankCode()) || !row.sourceAccountId().equals(request.sourceAccountId())) {
                throw new PayBridgeException(ErrorCode.CONFLICT, "Payout " + request.reference() + " already exists with different details");
            }
            return view(row);
        }

        AccountSummary source = accounts.lockAccounts(List.of(request.sourceAccountId())).get(0);
        if (!source.currency().equals(NGN)) {
            throw new PayBridgeException(ErrorCode.CURRENCY_MISMATCH, "A payout is paid from a naira account");
        }
        ledger.post(PostingRequest.of(EntryType.EXTERNAL_HOLD, request.reference(),
                "Hold for payout " + request.reference(),
                new PostingLine(request.sourceAccountId(), Money.ngn(-request.amountMinor())),
                new PostingLine(accounts.systemAccountId(SystemAccount.SUSPENSE), Money.ngn(request.amountMinor()))));
        return view(row);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<PayoutView> find(String reference) {
        return load(reference).map(SystemPayoutService::view);
    }

    /**
     * The payout's reference as we stored it, found ignoring case. Paystack only accepts lowercase references and sends the
     * lowercase text back in webhooks, while ours (for example PO-RM-...) are upper case.
     */
    @Transactional(readOnly = true)
    public Optional<String> canonicalReference(String reference) {
        return jdbc.queryForList("select reference from system_payout where lower(reference) = lower(?)", String.class, reference)
                .stream().findFirst();
    }

    @Transactional(readOnly = true)
    public Optional<Facts> facts(String reference) {
        return load(reference).map(row -> new Facts(row.amountMinor(), row.currency(), row.status()));
    }

    // ---- step 2: the dispatcher's building blocks

    @Transactional(readOnly = true)
    public List<String> dueReferences(int batchSize) {
        return jdbc.queryForList("""
                select reference from system_payout
                where dispatch_state = 'PENDING' and next_attempt_at <= ?
                order by next_attempt_at limit ?
                """, String.class, Timestamp.from(clock.instant()), batchSize);
    }

    /** Marks the payout as being sent and returns what to send, or empty if there is nothing (left) to send. */
    public Optional<Dispatch> beginDispatch(String reference) {
        Optional<Row> locked = jdbc.query("select * from system_payout where reference = ? for update", ROW, reference).stream().findFirst();
        if (locked.isEmpty()) {
            return Optional.empty();
        }
        Row row = locked.get();
        if (row.status().isFinal() || isDispatchDone(reference)) {
            jdbc.update("update system_payout set dispatch_state = 'DONE', updated_at = now() where reference = ?", reference);
            return Optional.empty();
        }
        if (row.status() == PayoutStatus.PENDING) {
            jdbc.update("update system_payout set status = 'PROCESSING', updated_at = now() where reference = ?", reference);
        }
        return Optional.of(new Dispatch(row.reference(), row.amountMinor(), row.currency(), row.bankCode(), row.accountNumber(),
                row.accountName(), row.narration()));
    }

    public void recordAccepted(String reference, String providerReference) {
        jdbc.update("""
                update system_payout set dispatch_state = 'DONE', provider_reference = coalesce(?, provider_reference), updated_at = now()
                where reference = ?
                """, providerReference, reference);
    }

    /** We could not get a clear answer. That is NOT a failure; try again later, and after too many tries leave it to the reconciler. */
    public void scheduleRetryOrGiveUp(String reference, String error) {
        Row row = load(reference).orElseThrow();
        String shortError = error == null ? null : error.substring(0, Math.min(error.length(), 255));
        if (row.attempts() + 1 >= maxAttempts) {
            jdbc.update("""
                    update system_payout set dispatch_state = 'DONE', attempts = attempts + 1, last_error = ?, updated_at = now()
                    where reference = ?
                    """, shortError, reference);
            log.warn("Giving up sending payout {} after {} attempts; the reconciler will ask the provider", reference, row.attempts() + 1);
            return;
        }
        Duration delay = baseDelay.multipliedBy(1L << Math.min(row.attempts(), 20));
        if (delay.compareTo(MAX_BACKOFF) > 0) {
            delay = MAX_BACKOFF;
        }
        jdbc.update("""
                update system_payout set attempts = attempts + 1, next_attempt_at = ?, last_error = ?, updated_at = now()
                where reference = ?
                """, Timestamp.from(clock.instant().plus(delay)), shortError, reference);
    }

    // ---- step 3: the provider's answer

    /** Safe to call repeatedly with the same answer. A contradiction (success after failure and so on) is INVALID_STATE: a person must look. */
    public void applyProviderResult(String reference, GatewayOutcome outcome, String providerReference, String message) {
        Row row = jdbc.query("select * from system_payout where reference = ? for update", ROW, reference).stream().findFirst()
                .orElseThrow(() -> new PayBridgeException(ErrorCode.RESOURCE_NOT_FOUND, "No payout " + reference));
        switch (outcome) {
            case SUCCESS -> applySuccess(row, providerReference);
            case FAILED -> applyFailure(row, message == null ? "Rejected by the payment provider" : message);
            case REVERSED -> {
                if (row.status() == PayoutStatus.SUCCESS) {
                    throw new PayBridgeException(ErrorCode.INVALID_STATE, "Payout " + reference
                            + " succeeded but the provider now says it was reversed: MANUAL REVIEW NEEDED");
                }
                applyFailure(row, message == null ? "Reversed by the payment provider" : message);
            }
            case PENDING, NOT_FOUND -> {
                // still waiting
            }
        }
    }

    /** The provider has no record of it, so it was never sent. Safe to release. */
    public void failIfNeverSent(String reference) {
        Row row = jdbc.query("select * from system_payout where reference = ? for update", ROW, reference).stream().findFirst()
                .orElseThrow(() -> new PayBridgeException(ErrorCode.RESOURCE_NOT_FOUND, "No payout " + reference));
        if (row.status() == PayoutStatus.PROCESSING) {
            applyFailure(row, "The payout never reached the payment provider");
        }
    }

    @Transactional(readOnly = true)
    public List<String> awaitingOutcome(Instant cutoff, int page, int batchSize) {
        return jdbc.queryForList("""
                select reference from system_payout
                where status = 'PROCESSING' and dispatch_state = 'DONE' and updated_at < ?
                order by updated_at limit ? offset ?
                """, String.class, Timestamp.from(cutoff), batchSize, (long) page * batchSize);
    }

    private void applySuccess(Row row, String providerReference) {
        if (row.status() == PayoutStatus.SUCCESS) {
            return;
        }
        if (row.status() == PayoutStatus.FAILED) {
            throw new PayBridgeException(ErrorCode.INVALID_STATE, "Payout " + row.reference()
                    + " was already refunded but the provider now says it succeeded: MANUAL REVIEW NEEDED");
        }
        ledger.post(PostingRequest.of(EntryType.EXTERNAL_SETTLEMENT, row.reference(), "Payout " + row.reference() + " paid out",
                new PostingLine(accounts.systemAccountId(SystemAccount.SUSPENSE), Money.ngn(-row.amountMinor())),
                new PostingLine(accounts.systemAccountId(SystemAccount.PAYSTACK_SETTLEMENT), Money.ngn(row.amountMinor()))));
        jdbc.update("""
                update system_payout set status = 'SUCCESS', dispatch_state = 'DONE',
                       provider_reference = coalesce(?, provider_reference), updated_at = now()
                where reference = ?
                """, providerReference, row.reference());
    }

    private void applyFailure(Row row, String reason) {
        if (row.status() == PayoutStatus.FAILED) {
            return;
        }
        if (row.status() == PayoutStatus.SUCCESS) {
            throw new PayBridgeException(ErrorCode.INVALID_STATE, "Payout " + row.reference()
                    + " succeeded but the provider now says it failed: MANUAL REVIEW NEEDED");
        }
        PostedEntry hold = ledger.find(EntryType.EXTERNAL_HOLD, row.reference())
                .orElseThrow(() -> new IllegalStateException("No hold entry for payout " + row.reference()));
        ledger.reverse(hold.id(), "release:" + row.reference(), "Refund: " + reason);
        String shortReason = reason.substring(0, Math.min(reason.length(), 255));
        jdbc.update("""
                update system_payout set status = 'FAILED', dispatch_state = 'DONE', failure_reason = ?, updated_at = now()
                where reference = ?
                """, shortReason, row.reference());
    }

    private boolean isDispatchDone(String reference) {
        Integer done = jdbc.queryForObject("select count(*) from system_payout where reference = ? and dispatch_state = 'DONE'",
                Integer.class, reference);
        return done != null && done > 0;
    }

    private Optional<Row> load(String reference) {
        return jdbc.query("select * from system_payout where reference = ?", ROW, reference).stream().findFirst();
    }

    private static PayoutView view(Row row) {
        return new PayoutView(row.reference(), row.status(), row.amountMinor(), row.failureReason());
    }

    private static void validate(PayoutRequest r) {
        if (r == null || r.reference() == null || !REFERENCE.matcher(r.reference()).matches()) {
            throw new PayBridgeException(ErrorCode.VALIDATION_FAILED, "A payout reference must start with " + PREFIX + " (4 to 37 more letters, digits, - or _)");
        }
        if (r.sourceAccountId() == null || r.amountMinor() <= 0) {
            throw new PayBridgeException(ErrorCode.VALIDATION_FAILED, "A payout needs a source account and a positive amount");
        }
        if (r.bankCode() == null || !r.bankCode().matches("\\d{3,6}") || r.accountNumber() == null || !r.accountNumber().matches("\\d{10}")) {
            throw new PayBridgeException(ErrorCode.VALIDATION_FAILED, "A payout needs a 3 to 6 digit bank code and a 10 digit account number");
        }
        if (r.accountName() == null || r.accountName().isBlank() || r.accountName().length() > 100
                || (r.narration() != null && r.narration().length() > 100)) {
            throw new PayBridgeException(ErrorCode.VALIDATION_FAILED, "A payout needs an account name of up to 100 characters");
        }
    }
}
