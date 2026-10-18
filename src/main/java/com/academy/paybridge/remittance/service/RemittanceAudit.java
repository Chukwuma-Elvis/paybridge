package com.academy.paybridge.remittance.service;

import com.academy.paybridge.remittance.api.RemittanceStatus;
import com.academy.paybridge.remittance.domain.Remittance;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * The history of a remittance: every move between statuses, written in the same transaction as the move and never edited afterwards.
 * Moves go through {@link #move}, so the status change and its record cannot come apart.
 */
@Component
public class RemittanceAudit {

    private final JdbcTemplate jdbc;
    private final MeterRegistry meters;

    RemittanceAudit(JdbcTemplate jdbc, MeterRegistry meters) {
        this.jdbc = jdbc;
        this.meters = meters;
        for (RemittanceStatus status : RemittanceStatus.values()) {
            Gauge.builder("paybridge.remittance.by.status", () -> countIn(status))
                    .description("How many remittances are in each status right now")
                    .tag("status", status.name())
                    .register(meters);
        }
    }

    private double countIn(RemittanceStatus status) {
        try {
            Integer count = jdbc.queryForObject("select count(*) from remittance where status = ?", Integer.class, status.name());
            return count == null ? 0 : count;
        } catch (RuntimeException e) {
            return -1;
        }
    }

    /** Changes the remittance's status (checked by the state machine) and records the move. */
    void move(Remittance remittance, RemittanceStatus next, String note) {
        RemittanceStatus from = remittance.getStatus();
        remittance.moveTo(next, note);
        record(remittance.getId(), from, next, note);
    }

    void recordCreated(Remittance remittance) {
        record(remittance.getId(), null, RemittanceStatus.CREATED, null);
    }

    /** The trail for one remittance, oldest first, as {@code "FROM -> TO: note"} lines. */
    public List<String> history(UUID remittanceId) {
        return jdbc.query("select from_status, to_status, note from remittance_event where remittance_id = ? order by created_at, id",
                (rs, n) -> (rs.getString("from_status") == null ? "-" : rs.getString("from_status")) + " -> " + rs.getString("to_status")
                        + (rs.getString("note") == null ? "" : ": " + rs.getString("note")), remittanceId);
    }

    private void record(UUID id, RemittanceStatus from, RemittanceStatus to, String note) {
        jdbc.update("insert into remittance_event (id, remittance_id, from_status, to_status, note, created_at) "
                        + "values (?, ?, ?, ?, ?, clock_timestamp())", UUID.randomUUID(), id, from == null ? null : from.name(), to.name(),
                note == null ? null : note.substring(0, Math.min(note.length(), 255)));
        countAfterCommit(to);
    }

    /** The counter goes up only once the move is really committed: a creation that is rolled back is never counted. */
    private void countAfterCommit(RemittanceStatus to) {
        Runnable count = () -> meters.counter("paybridge.remittance.transitions", "to", to.name().toLowerCase(Locale.ROOT)).increment();
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    count.run();
                }
            });
        } else {
            count.run();
        }
    }
}
