package com.academy.paybridge.settlement.service;

import com.academy.paybridge.account.api.AccountApi;
import com.academy.paybridge.ledger.api.AccountLineView;
import com.academy.paybridge.ledger.api.EntryType;
import com.academy.paybridge.ledger.api.LedgerApi;
import com.academy.paybridge.ledger.api.PostedEntry;
import com.academy.paybridge.ledger.api.PostingLine;
import com.academy.paybridge.ledger.api.PostingRequest;
import com.academy.paybridge.settlement.service.SettlementViews.BatchLineView;
import com.academy.paybridge.settlement.service.SettlementViews.BatchReport;
import com.academy.paybridge.settlement.service.SettlementViews.BatchView;
import com.academy.paybridge.settlement.service.SettlementViews.Position;
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

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Currency;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Batches: a frozen slice of the movements on a partner's settlement account.
 * <p>
 * The partner's account is the ledger's record of what the partner holds for PayBridge (or owes the partner): funding makes it more
 * negative, a refund less. At a cut-off every line not yet in a batch becomes part of the next one, so a line is in exactly one batch
 * (the database enforces it with a unique constraint), and a line that was still being committed at the cut-off simply goes into the
 * following batch. When the partner has paid, one SETTLEMENT_PAYMENT entry brings the batch's net back to zero.
 */
@Service
@Transactional
public class BatchService {

    static final String ACCOUNT_PREFIX = "PARTNER_SETTLEMENT_USD:";
    private static final Logger log = LoggerFactory.getLogger(BatchService.class);
    private static final Pattern PARTNER_CODE = Pattern.compile("[a-z0-9_-]{2,16}");
    private static final int MAX_LINES_PER_BATCH = 100_000;

    private static final RowMapper<BatchView> BATCH = (rs, n) -> new BatchView(
            rs.getObject("id", UUID.class), rs.getString("partner_code"), rs.getInt("sequence"), rs.getString("currency"),
            rs.getString("status"), instant(rs.getObject("cutoff_at", OffsetDateTime.class)), rs.getLong("opening_minor"),
            rs.getLong("movements_minor"), rs.getLong("closing_minor"), rs.getInt("line_count"),
            rs.getObject("payment_entry_id", UUID.class), rs.getString("dispute_note"),
            instant(rs.getObject("closed_at", OffsetDateTime.class)), instant(rs.getObject("instructed_at", OffsetDateTime.class)),
            instant(rs.getObject("confirmed_at", OffsetDateTime.class)), instant(rs.getObject("reconciled_at", OffsetDateTime.class)));

    private static final RowMapper<BatchLineView> LINE = (rs, n) -> new BatchLineView(
            rs.getObject("ledger_line_id", UUID.class), rs.getObject("entry_id", UUID.class), rs.getString("entry_type"),
            rs.getString("reference"), rs.getLong("amount_minor"), rs.getString("currency"),
            instant(rs.getObject("posted_at", OffsetDateTime.class)));

    private final JdbcTemplate jdbc;
    private final LedgerApi ledger;
    private final AccountApi accounts;
    private final SettlementProperties properties;
    private final MeterRegistry meters;
    private final Clock clock;

    BatchService(JdbcTemplate jdbc, LedgerApi ledger, AccountApi accounts, SettlementProperties properties, MeterRegistry meters,
                 Clock clock) {
        this.jdbc = jdbc;
        this.ledger = ledger;
        this.accounts = accounts;
        this.properties = properties;
        this.meters = meters;
        this.clock = clock;
    }

    static Instant instant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    // ---- closing a batch

    /**
     * Freezes every movement on the partner's account before {@code cutoff} that is not yet in a batch into a new batch. Closing the
     * same cut-off again returns the same batch and changes nothing; with nothing new to freeze there is no batch.
     */
    public Optional<BatchView> close(String partnerCode, Instant cutoff) {
        String code = requirePartner(partnerCode);
        UUID accountId = ensurePartner(code);
        // one closer at a time per partner; everyone else waits here and then finds the batch already made
        jdbc.queryForObject("select code from settlement_partner where code = ? for update", String.class, code);

        Optional<BatchView> existing = jdbc.query("select * from settlement_batch where partner_code = ? and cutoff_at = ?", BATCH, code,
                Timestamp.from(cutoff)).stream().findFirst();
        if (existing.isPresent()) {
            return existing;
        }

        Set<UUID> alreadyBatched = new HashSet<>(jdbc.queryForList("""
                select bl.ledger_line_id from settlement_batch_line bl join settlement_batch b on b.id = bl.batch_id
                where b.partner_code = ?
                """, UUID.class, code));
        List<AccountLineView> fresh = ledger.linesBefore(accountId, cutoff, MAX_LINES_PER_BATCH).stream()
                .filter(line -> !alreadyBatched.contains(line.lineId())).toList();
        if (fresh.isEmpty()) {
            return Optional.empty();
        }

        Integer lastSequence = jdbc.queryForObject("select coalesce(max(sequence), 0) from settlement_batch where partner_code = ?",
                Integer.class, code);
        long opening = jdbc.query("select closing_minor from settlement_batch where partner_code = ? order by sequence desc limit 1",
                (rs, n) -> rs.getLong(1), code).stream().findFirst().orElse(0L);
        long movements = fresh.stream().mapToLong(AccountLineView::amountMinor).sum();
        UUID batchId = UUID.randomUUID();
        jdbc.update("""
                insert into settlement_batch (id, partner_code, sequence, currency, status, cutoff_at, opening_minor, movements_minor,
                                              closing_minor, line_count, closed_at)
                values (?, ?, ?, ?, 'CLOSED', ?, ?, ?, ?, ?, now())
                """, batchId, code, lastSequence + 1, fresh.get(0).currency(), Timestamp.from(cutoff), opening, movements,
                opening + movements, fresh.size());
        jdbc.batchUpdate("""
                insert into settlement_batch_line (id, batch_id, ledger_line_id, entry_id, entry_type, reference, amount_minor, currency,
                                                   posted_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, fresh, 500, (ps, line) -> {
            ps.setObject(1, UUID.randomUUID());
            ps.setObject(2, batchId);
            ps.setObject(3, line.lineId());
            ps.setObject(4, line.entryId());
            ps.setString(5, line.type().name());
            ps.setString(6, line.reference());
            ps.setLong(7, line.amountMinor());
            ps.setString(8, line.currency());
            ps.setTimestamp(9, Timestamp.from(line.createdAt()));
        });
        meters.counter("paybridge.settlement.batches", "event", "closed").increment();
        log.info("Settlement batch {} #{} closed for partner {}: {} lines", batchId, lastSequence + 1, code, fresh.size());
        return Optional.of(get(batchId));
    }

    // ---- paying it

    /** CLOSED to INSTRUCTED: the payment has been requested from the partner. Repeating it changes nothing. */
    public BatchView instruct(UUID batchId) {
        BatchView batch = lock(batchId);
        if (!batch.status().equals("CLOSED")) {
            return batch;      // already asked for (or further on): nothing more to do
        }
        jdbc.update("update settlement_batch set status = 'INSTRUCTED', instructed_at = now() where id = ? and status = 'CLOSED'", batchId);
        return get(batchId);
    }

    /**
     * INSTRUCTED to CONFIRMED: the partner has paid the batch's net. Posts ONE settlement payment that brings the batch's movements
     * back to zero on the partner's account. Repeating it posts nothing more.
     */
    public BatchView confirm(UUID batchId) {
        BatchView batch = lock(batchId);
        switch (batch.status()) {
            case "CONFIRMED", "RECONCILED", "DISPUTED" -> {
                return batch;
            }
            case "CLOSED" -> throw new PayBridgeException(ErrorCode.INVALID_STATE,
                    "The payment must be requested (instructed) before it can be confirmed");
            default -> {
                // INSTRUCTED: carry on
            }
        }
        UUID paymentEntry = null;
        long net = netToSettle(batchId);
        if (net != 0) {
            Currency currency = Currency.getInstance(batch.currency());
            UUID partnerAccount = accounts.systemAccountIdByCode(ACCOUNT_PREFIX + batch.partnerCode());
            UUID cash = accounts.ensureSystemAccount("SETTLEMENT_CASH_" + batch.currency(), batch.currency());
            // the partner's account is brought back by the batch's net; the cash account is the mirror image
            PostedEntry entry = ledger.post(PostingRequest.of(EntryType.SETTLEMENT_PAYMENT,
                    "SETTLE-" + batch.partnerCode().toUpperCase(Locale.ROOT) + "-" + batch.sequence(),
                    "Settlement of batch " + batch.sequence() + " with " + batch.partnerCode(),
                    new PostingLine(partnerAccount, Money.ofMinor(-net, currency)),
                    new PostingLine(cash, Money.ofMinor(net, currency))));
            paymentEntry = entry.id();
        }
        jdbc.update("""
                update settlement_batch set status = 'CONFIRMED', confirmed_at = now(), payment_entry_id = ?
                where id = ? and status = 'INSTRUCTED'
                """, paymentEntry, batchId);
        meters.counter("paybridge.settlement.batches", "event", "confirmed").increment();
        reconcileIfClean(batchId);
        return get(batchId);
    }

    /**
     * What this batch obliges the two sides to pay: the sum of its lines EXCLUDING earlier settlement payments. A payment line is the
     * settling of an obligation that was already counted in an earlier batch; counting it again would pay it twice.
     */
    @Transactional(readOnly = true)
    public long netToSettle(UUID batchId) {
        Long net = jdbc.queryForObject("select coalesce(sum(amount_minor), 0) from settlement_batch_line "
                + "where batch_id = ? and entry_type <> 'SETTLEMENT_PAYMENT'", Long.class, batchId);
        return net == null ? 0 : net;
    }

    /** Marks a paid batch whose differences cannot be settled yet as DISPUTED, with a note. */
    public BatchView dispute(UUID batchId, String note) {
        if (note == null || note.isBlank() || note.length() > 500) {
            throw new PayBridgeException(ErrorCode.VALIDATION_FAILED, "A dispute needs a note of up to 500 characters");
        }
        BatchView batch = lock(batchId);
        if (batch.status().equals("DISPUTED")) {
            return batch;
        }
        if (!batch.status().equals("CONFIRMED")) {
            throw new PayBridgeException(ErrorCode.INVALID_STATE, "Only a confirmed batch can be disputed, but this one is " + batch.status());
        }
        Integer open = jdbc.queryForObject("select count(*) from settlement_break where batch_id = ? and status = 'OPEN'", Integer.class,
                batchId);
        if (open == null || open == 0) {
            throw new PayBridgeException(ErrorCode.INVALID_STATE, "There are no open breaks to dispute");
        }
        jdbc.update("update settlement_batch set status = 'DISPUTED', dispute_note = ? where id = ? and status = 'CONFIRMED'", note.trim(),
                batchId);
        return get(batchId);
    }

    /**
     * RECONCILED needs all of: the batch is paid (CONFIRMED or DISPUTED), a statement was loaded, and no break is still open. Calling it
     * when that is not true, or when it is already so, changes nothing. Returns true if the batch just became RECONCILED.
     */
    public boolean reconcileIfClean(UUID batchId) {
        int moved = jdbc.update("""
                update settlement_batch b set status = 'RECONCILED', reconciled_at = now()
                where b.id = ? and b.status in ('CONFIRMED', 'DISPUTED')
                  and exists (select 1 from settlement_statement s where s.batch_id = b.id)
                  and not exists (select 1 from settlement_break k where k.batch_id = b.id and k.status = 'OPEN')
                """, batchId);
        if (moved == 1) {
            meters.counter("paybridge.settlement.batches", "event", "reconciled").increment();
        }
        return moved == 1;
    }

    // ---- reading

    @Transactional(readOnly = true)
    public BatchView get(UUID batchId) {
        return jdbc.query("select * from settlement_batch where id = ?", BATCH, batchId).stream().findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("Settlement batch", batchId));
    }

    @Transactional(readOnly = true)
    public List<BatchView> list(String partnerCode, int limit) {
        int bounded = Math.max(1, Math.min(limit, 200));
        return partnerCode == null
                ? jdbc.query("select * from settlement_batch order by closed_at desc, sequence desc limit ?", BATCH, bounded)
                : jdbc.query("select * from settlement_batch where partner_code = ? order by sequence desc limit ?", BATCH,
                partnerCode.toLowerCase(Locale.ROOT), bounded);
    }

    @Transactional(readOnly = true)
    public List<BatchLineView> lines(UUID batchId) {
        get(batchId);
        return jdbc.query("select * from settlement_batch_line where batch_id = ? order by posted_at, id", LINE, batchId);
    }

    @Transactional(readOnly = true)
    public Position position(String partnerCode) {
        String code = requirePartner(partnerCode);
        UUID accountId = accounts.systemAccountIdByCode(ACCOUNT_PREFIX + code);
        long balance = accounts.getAccount(accountId).balanceMinor();
        Long batched = jdbc.queryForObject("""
                select coalesce(sum(bl.amount_minor), 0) from settlement_batch_line bl join settlement_batch b on b.id = bl.batch_id
                where b.partner_code = ?
                """, Long.class, code);
        long unbatched = balance - (batched == null ? 0 : batched);
        Set<UUID> done = new HashSet<>(jdbc.queryForList("""
                select bl.ledger_line_id from settlement_batch_line bl join settlement_batch b on b.id = bl.batch_id
                where b.partner_code = ?
                """, UUID.class, code));
        int waiting = (int) ledger.linesBefore(accountId, clock.instant().plusSeconds(60), MAX_LINES_PER_BATCH).stream()
                .filter(line -> !done.contains(line.lineId())).count();
        return new Position(code, "USD", balance, batched == null ? 0 : batched, unbatched, waiting);
    }

    @Transactional(readOnly = true)
    public BatchReport report(UUID batchId) {
        BatchView batch = get(batchId);
        int statementLines = count("select count(*) from settlement_statement_line l join settlement_statement s on s.id = l.statement_id "
                + "where s.batch_id = ?", batchId);
        int matched = count("select count(*) from settlement_statement_line l join settlement_statement s on s.id = l.statement_id "
                + "where s.batch_id = ? and l.match_status = 'MATCHED'", batchId);
        int open = count("select count(*) from settlement_break where batch_id = ? and status = 'OPEN'", batchId);
        int resolved = count("select count(*) from settlement_break where batch_id = ? and status = 'RESOLVED'", batchId);

        Map<String, Integer> byAge = new LinkedHashMap<>();
        byAge.put("0-1d", 0);
        byAge.put("2-7d", 0);
        byAge.put(">7d", 0);
        Instant now = clock.instant();
        for (Instant created : jdbc.query("select created_at from settlement_break where batch_id = ? and status = 'OPEN'",
                (rs, n) -> instant(rs.getObject(1, OffsetDateTime.class)), batchId)) {
            long days = java.time.Duration.between(created, now).toDays();
            byAge.merge(days <= 1 ? "0-1d" : days <= 7 ? "2-7d" : ">7d", 1, Integer::sum);
        }

        UUID accountId = accounts.systemAccountIdByCode(ACCOUNT_PREFIX + batch.partnerCode());
        long ledgerSum = ledger.linesBefore(accountId, batch.cutoffAt(), MAX_LINES_PER_BATCH).stream()
                .mapToLong(AccountLineView::amountMinor).sum();
        return new BatchReport(batch.id(), batch.partnerCode(), batch.sequence(), batch.status(), batch.currency(),
                batch.cutoffAt().atOffset(ZoneOffset.UTC).toLocalDate(), batch.openingMinor(), batch.movementsMinor(),
                batch.closingMinor(), netToSettle(batchId), batch.lineCount(), statementLines, matched, open, resolved, byAge,
                ledgerSum == batch.closingMinor());
    }

    // ---- helpers shared with the other settlement services

    BatchView lock(UUID batchId) {
        return jdbc.query("select * from settlement_batch where id = ? for update", BATCH, batchId).stream().findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("Settlement batch", batchId));
    }

    private String requirePartner(String partnerCode) {
        String code = partnerCode == null ? "" : partnerCode.toLowerCase(Locale.ROOT);
        if (!PARTNER_CODE.matcher(code).matches() || !properties.partners().stream().map(p -> p.toLowerCase(Locale.ROOT)).toList().contains(code)) {
            throw new PayBridgeException(ErrorCode.RESOURCE_NOT_FOUND, "Unknown settlement partner");
        }
        return code;
    }

    private UUID ensurePartner(String code) {
        UUID accountId = accounts.ensureSystemAccount(ACCOUNT_PREFIX + code, "USD");
        jdbc.update("insert into settlement_partner (code, currency, account_id, created_at) values (?, 'USD', ?, now()) "
                + "on conflict (code) do nothing", code, accountId);
        return accountId;
    }

    private int count(String sql, Object... args) {
        Integer value = jdbc.queryForObject(sql, Integer.class, args);
        return value == null ? 0 : value;
    }
}
