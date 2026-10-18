package com.academy.paybridge.settlement.service;

import com.academy.paybridge.settlement.service.SettlementViews.BatchView;
import com.academy.paybridge.settlement.service.SettlementViews.StatementResult;
import com.academy.paybridge.settlement.service.StatementMatcher.Difference;
import com.academy.paybridge.shared.exception.ErrorCode;
import com.academy.paybridge.shared.exception.PayBridgeException;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Date;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Loads a partner's statement for a batch and compares it with our own record of the same movements.
 * <p>
 * Loading is all-or-nothing and idempotent: a statement with any bad row imports nothing; the very same file twice changes nothing;
 * a different file for a batch that already has one is refused. Every statement line and every line of ours ends in a defined outcome,
 * and each difference becomes a break that someone must resolve.
 */
@Service
@Transactional
public class StatementService {

    private static final Logger log = LoggerFactory.getLogger(StatementService.class);

    private final JdbcTemplate jdbc;
    private final BatchService batches;
    private final SettlementProperties properties;
    private final MeterRegistry meters;

    StatementService(JdbcTemplate jdbc, BatchService batches, SettlementProperties properties, MeterRegistry meters) {
        this.jdbc = jdbc;
        this.batches = batches;
        this.properties = properties;
        this.meters = meters;
    }

    private record OurRow(UUID batchLineId, String reference, long ledgerAmount, java.time.LocalDate date) {
    }

    public StatementResult upload(UUID batchId, byte[] content, String uploadedBy) {
        if (content == null || content.length == 0) {
            throw new PayBridgeException(ErrorCode.VALIDATION_FAILED, "The statement is empty");
        }
        if (content.length > properties.statement().maxBytes()) {
            throw new PayBridgeException(ErrorCode.PAYLOAD_TOO_LARGE, "The statement is larger than " + properties.statement().maxBytes() + " bytes");
        }
        BatchView batch = batches.lock(batchId);          // one upload at a time per batch
        String fingerprint = sha256(content);

        Optional<Map<String, Object>> earlier = jdbc.queryForList("select id, fingerprint, line_count from settlement_statement where batch_id = ?",
                batchId).stream().findFirst();
        if (earlier.isPresent()) {
            if (earlier.get().get("fingerprint").equals(fingerprint)) {
                return summarise((UUID) earlier.get().get("id"), true);
            }
            throw new PayBridgeException(ErrorCode.CONFLICT, "This batch already has a different statement loaded");
        }
        if (batch.status().equals("RECONCILED")) {
            throw new PayBridgeException(ErrorCode.INVALID_STATE, "This batch is already reconciled");
        }

        StatementParser.Result parsed = StatementParser.parse(content, properties.statement().maxRows(), batch.currency());
        if (!parsed.isValid()) {
            meters.counter("paybridge.settlement.statements", "result", "rejected").increment();
            throw new PayBridgeException(ErrorCode.VALIDATION_FAILED,
                    "The statement was not imported. " + String.join("; ", parsed.errors()));
        }

        List<OurRow> ours = jdbc.query("select id, reference, amount_minor, posted_at from settlement_batch_line where batch_id = ? order by posted_at, id",
                (rs, n) -> new OurRow(rs.getObject("id", UUID.class), normalise(rs.getString("reference")), rs.getLong("amount_minor"),
                        rs.getObject("posted_at", OffsetDateTime.class).withOffsetSameInstant(ZoneOffset.UTC).toLocalDate()), batchId);

        // ids for the statement lines are made here so the matcher's answer can be written straight back
        List<StatementMatcher.Theirs> theirs = new ArrayList<>();
        Map<UUID, StatementParser.Row> rowOf = new HashMap<>();
        for (StatementParser.Row row : parsed.rows()) {
            UUID id = UUID.randomUUID();
            theirs.add(new StatementMatcher.Theirs(id, row.rowNumber(), row.reference(), row.amountMinor(), row.valueDate()));
            rowOf.put(id, row);
        }
        List<StatementMatcher.Ours> comparable = ours.stream()
                .map(o -> new StatementMatcher.Ours(o.batchLineId(), o.reference(), -o.ledgerAmount(), o.date())).toList();
        StatementMatcher.Result result = StatementMatcher.match(comparable, theirs, properties.tolerance().amountMinor(),
                properties.tolerance().dateDays());

        UUID statementId = UUID.randomUUID();
        jdbc.update("insert into settlement_statement (id, batch_id, fingerprint, line_count, uploaded_by, created_at) values (?, ?, ?, ?, ?, now())",
                statementId, batchId, fingerprint, theirs.size(), uploadedBy);

        Map<UUID, UUID> matchedOurs = new HashMap<>();      // our line -> the statement line that covers it
        result.matched().forEach((theirId, ourIds) -> ourIds.forEach(ourId -> matchedOurs.put(ourId, theirId)));
        Map<UUID, Difference> differenceOf = new HashMap<>();
        result.differences().forEach(d -> differenceOf.put(d.theirs(), d));

        for (StatementMatcher.Theirs t : theirs) {
            StatementParser.Row row = rowOf.get(t.id());
            String status = result.matched().containsKey(t.id()) ? "MATCHED"
                    : differenceOf.containsKey(t.id()) ? "AMOUNT_DIFFERS" : "UNMATCHED_THEIRS";
            UUID matchedLine = result.matched().containsKey(t.id()) ? result.matched().get(t.id()).get(0)
                    : differenceOf.containsKey(t.id()) ? differenceOf.get(t.id()).ours() : null;
            jdbc.update("""
                    insert into settlement_statement_line (id, statement_id, row_number, reference, amount_minor, currency, value_date,
                                                           match_status, matched_batch_line_id)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, t.id(), statementId, row.rowNumber(), row.reference(), row.amountMinor(), row.currency(),
                    Date.valueOf(row.valueDate()), status, matchedLine);
        }

        int breaks = 0;
        Map<UUID, OurRow> ourById = new HashMap<>();
        ours.forEach(o -> ourById.put(o.batchLineId(), o));
        for (Difference d : result.differences()) {
            OurRow our = ourById.get(d.ours());
            long theirAmount = rowOf.get(d.theirs()).amountMinor();
            openBreak(batchId, "AMOUNT_DIFFERS", d.ours(), d.theirs(), -our.ledgerAmount(), theirAmount,
                    "Amounts differ by " + Math.abs(-our.ledgerAmount() - theirAmount) + " minor units (reference " + our.reference() + ")");
            breaks++;
        }
        for (UUID ourId : result.unmatchedOurs()) {
            OurRow our = ourById.get(ourId);
            openBreak(batchId, "UNMATCHED_OURS", ourId, null, -our.ledgerAmount(), null,
                    "We booked this but the partner's statement does not show it (reference " + our.reference() + ")");
            breaks++;
        }
        for (Map.Entry<UUID, String> e : result.unmatchedTheirs().entrySet()) {
            StatementParser.Row row = rowOf.get(e.getKey());
            openBreak(batchId, "UNMATCHED_THEIRS", null, e.getKey(), null, row.amountMinor(), e.getValue() + " (row " + row.rowNumber() + ")");
            breaks++;
        }

        meters.counter("paybridge.settlement.statements", "result", "loaded").increment();
        log.info("Statement loaded for batch {}: {} lines, {} breaks", batchId, theirs.size(), breaks);
        batches.reconcileIfClean(batchId);
        return summarise(statementId, false);
    }

    private void openBreak(UUID batchId, String type, UUID batchLineId, UUID statementLineId, Long ours, Long theirs, String reason) {
        jdbc.update("""
                insert into settlement_break (id, batch_id, break_type, batch_line_id, statement_line_id, our_amount_minor,
                                              their_amount_minor, reason, status, created_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, 'OPEN', now())
                """, UUID.randomUUID(), batchId, type, batchLineId, statementLineId, ours, theirs,
                reason.substring(0, Math.min(reason.length(), 255)));
        meters.counter("paybridge.settlement.breaks", "type", type.toLowerCase(java.util.Locale.ROOT)).increment();
    }

    private StatementResult summarise(UUID statementId, boolean duplicate) {
        UUID batchId = jdbc.queryForObject("select batch_id from settlement_statement where id = ?", UUID.class, statementId);
        int lines = count("select count(*) from settlement_statement_line where statement_id = ?", statementId);
        int matched = count("select count(*) from settlement_statement_line where statement_id = ? and match_status = 'MATCHED'", statementId);
        int differs = count("select count(*) from settlement_statement_line where statement_id = ? and match_status = 'AMOUNT_DIFFERS'", statementId);
        int theirsOnly = count("select count(*) from settlement_statement_line where statement_id = ? and match_status = 'UNMATCHED_THEIRS'", statementId);
        int oursOnly = count("select count(*) from settlement_break where batch_id = ? and break_type = 'UNMATCHED_OURS'", batchId);
        int open = count("select count(*) from settlement_break where batch_id = ? and status = 'OPEN'", batchId);
        return new StatementResult(statementId, duplicate, lines, matched, differs, oursOnly, theirsOnly, open);
    }

    /** Our reference for a refund is "refund:RM-..."; the partner knows the movement by the remittance reference alone. */
    static String normalise(String reference) {
        return reference != null && reference.startsWith("refund:") ? reference.substring("refund:".length()) : reference;
    }

    private int count(String sql, Object... args) {
        Integer value = jdbc.queryForObject(sql, Integer.class, args);
        return value == null ? 0 : value;
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always available", e);
        }
    }
}
