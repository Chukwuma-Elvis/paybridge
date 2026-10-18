package com.academy.paybridge.settlement.service;

import com.academy.paybridge.account.api.AccountApi;
import com.academy.paybridge.account.api.SystemAccount;
import com.academy.paybridge.ledger.api.EntryType;
import com.academy.paybridge.ledger.api.LedgerApi;
import com.academy.paybridge.ledger.api.PostedEntry;
import com.academy.paybridge.ledger.api.PostingLine;
import com.academy.paybridge.ledger.api.PostingRequest;
import com.academy.paybridge.settlement.service.SettlementViews.BatchView;
import com.academy.paybridge.settlement.service.SettlementViews.BreakView;
import com.academy.paybridge.shared.exception.ErrorCode;
import com.academy.paybridge.shared.exception.PayBridgeException;
import com.academy.paybridge.shared.exception.ResourceNotFoundException;
import com.academy.paybridge.shared.money.Money;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Currency;
import java.util.List;
import java.util.UUID;

/**
 * Breaks: the differences found when a statement is compared with our record. A break is never silently dropped; it is resolved by a
 * person with a written note and, where money really differs, a correcting ledger entry posted through the normal ledger path (so the
 * books keep summing to zero). A resolved break cannot be resolved again.
 */
@Service
@Transactional
public class BreakService {

    private static final Logger log = LoggerFactory.getLogger(BreakService.class);

    private static final RowMapper<BreakView> ROW = (rs, n) -> new BreakView(
            rs.getObject("id", UUID.class), rs.getObject("batch_id", UUID.class), rs.getString("break_type"),
            rs.getObject("batch_line_id", UUID.class), rs.getObject("statement_line_id", UUID.class),
            (Long) rs.getObject("our_amount_minor"), (Long) rs.getObject("their_amount_minor"), rs.getString("reason"),
            rs.getString("status"), rs.getString("owner"), rs.getString("resolution_note"), rs.getString("resolved_by"),
            BatchService.instant(rs.getObject("resolved_at", OffsetDateTime.class)), rs.getObject("adjustment_entry_id", UUID.class),
            BatchService.instant(rs.getObject("created_at", OffsetDateTime.class)));

    private final JdbcTemplate jdbc;
    private final BatchService batches;
    private final LedgerApi ledger;
    private final AccountApi accounts;
    private final MeterRegistry meters;

    BreakService(JdbcTemplate jdbc, BatchService batches, LedgerApi ledger, AccountApi accounts, MeterRegistry meters) {
        this.jdbc = jdbc;
        this.batches = batches;
        this.ledger = ledger;
        this.accounts = accounts;
        this.meters = meters;
    }

    @Transactional(readOnly = true)
    public List<BreakView> list(UUID batchId, String status) {
        batches.get(batchId);
        return status == null
                ? jdbc.query("select * from settlement_break where batch_id = ? order by created_at, id", ROW, batchId)
                : jdbc.query("select * from settlement_break where batch_id = ? and status = ? order by created_at, id", ROW, batchId, status);
    }

    @Transactional(readOnly = true)
    public BreakView get(UUID breakId) {
        return jdbc.query("select * from settlement_break where id = ?", ROW, breakId).stream().findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("Settlement break", breakId));
    }

    /** Puts a name against a break so it is somebody's job. */
    public BreakView assign(UUID breakId, String owner) {
        if (owner == null || owner.isBlank() || owner.length() > 100) {
            throw new PayBridgeException(ErrorCode.VALIDATION_FAILED, "An owner of up to 100 characters is required");
        }
        get(breakId);
        jdbc.update("update settlement_break set owner = ? where id = ? and status = 'OPEN'", owner.trim(), breakId);
        return get(breakId);
    }

    /**
     * Resolves an open break with a mandatory note.
     *
     * @param adjust when true, also posts a correcting entry that brings our books to the partner's figure: the partner account moves
     *               by (our figure minus their figure) against the settlement adjustment account
     */
    public BreakView resolve(UUID breakId, String note, boolean adjust, String resolvedBy) {
        if (note == null || note.isBlank()) {
            throw new PayBridgeException(ErrorCode.VALIDATION_FAILED, "A written note is required to resolve a break");
        }
        if (note.length() > 500) {
            throw new PayBridgeException(ErrorCode.VALIDATION_FAILED, "The note may be at most 500 characters");
        }
        BreakView current = get(breakId);
        if (current.status().equals("RESOLVED")) {
            throw new PayBridgeException(ErrorCode.INVALID_STATE, "This break was already resolved");
        }
        BatchView batch = batches.lock(current.batchId());

        long correction = 0;
        if (adjust) {
            long ours = current.ourAmountMinor() == null ? 0 : current.ourAmountMinor();
            long theirs = current.theirAmountMinor() == null ? 0 : current.theirAmountMinor();
            correction = ours - theirs;
            if (correction == 0) {
                throw new PayBridgeException(ErrorCode.VALIDATION_FAILED, "There is no difference in money to correct; resolve with a note only");
            }
        }

        // one statement that only succeeds while the break is open, so two people cannot both resolve it
        int claimed = jdbc.update("""
                update settlement_break set status = 'RESOLVED', resolution_note = ?, resolved_by = ?, resolved_at = now()
                where id = ? and status = 'OPEN'
                """, note.trim(), resolvedBy, breakId);
        if (claimed == 0) {
            throw new PayBridgeException(ErrorCode.INVALID_STATE, "This break was already resolved");
        }

        if (adjust) {
            Currency currency = Currency.getInstance(batch.currency());
            UUID partnerAccount = accounts.systemAccountIdByCode(BatchService.ACCOUNT_PREFIX + batch.partnerCode());
            UUID adjustment = accounts.systemAccountId(SystemAccount.SETTLEMENT_ADJUSTMENT_USD);
            PostedEntry entry = ledger.post(PostingRequest.of(EntryType.SETTLEMENT_ADJUSTMENT, "ADJ-" + breakId,
                    "Correction for settlement break " + breakId,
                    new PostingLine(partnerAccount, Money.ofMinor(correction, currency)),
                    new PostingLine(adjustment, Money.ofMinor(-correction, currency))));
            jdbc.update("update settlement_break set adjustment_entry_id = ? where id = ?", entry.id(), breakId);
        }
        meters.counter("paybridge.settlement.breaks.resolved", "adjusted", String.valueOf(adjust)).increment();
        log.info("Settlement break {} resolved by {}{}", breakId, resolvedBy, adjust ? " with a correcting entry" : "");
        batches.reconcileIfClean(current.batchId());
        return get(breakId);
    }
}
