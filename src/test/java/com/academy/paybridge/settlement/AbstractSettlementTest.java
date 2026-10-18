package com.academy.paybridge.settlement;

import com.academy.paybridge.remittance.AbstractRemittanceTest;
import com.academy.paybridge.remittance.api.RemittanceView;
import com.academy.paybridge.settlement.service.BatchService;
import com.academy.paybridge.settlement.service.BreakService;
import com.academy.paybridge.settlement.service.SettlementViews.BatchLineView;
import com.academy.paybridge.settlement.service.SettlementViews.BatchView;
import com.academy.paybridge.settlement.service.StatementService;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Shared set-up for the settlement tests. Every test starts from a clean slate: everything the partner's account held is batched,
 * paid and swept, so a test's batch holds only the lines that test made.
 * <p>
 * Statement amounts use the partner's sign: what the partner holds for us is positive, so a funding of USD 1,000.00 is {@code 100000}.
 */
public abstract class AbstractSettlementTest extends AbstractRemittanceTest {

    @Autowired protected BatchService batches;
    @Autowired protected StatementService statements;
    @Autowired protected BreakService breaks;

    @BeforeEach
    void startFromACleanSlate() {
        settleEverything();
    }

    /** Batches, instructs and confirms everything outstanding until the partner's account is square and nothing is waiting. */
    protected void settleEverything() {
        for (int round = 0; round < 3; round++) {
            batches.close(PARTNER, Instant.now());
            for (BatchView batch : batches.list(PARTNER, 200)) {
                if (batch.status().equals("CLOSED")) {
                    batches.instruct(batch.id());
                    batches.confirm(batch.id());
                } else if (batch.status().equals("INSTRUCTED")) {
                    batches.confirm(batch.id());
                }
            }
        }
    }

    /** A remittance the partner has funded (so its dollars are on the partner's account). */
    protected RemittanceView funded(long sell) throws Exception {
        RemittanceView view = create(command(newQuote(sell), "Chidi Okafor", "NG", paybridgeRecipient(newAccountNumber()))).remittance();
        String result = partnerWebhook(PARTNER_SECRET, fundingBody(PARTNER, "evt-" + UUID.randomUUID(), "funding.confirmed",
                view.reference(), sell, "USD"));
        if (!result.equals("APPLIED")) {
            throw new IllegalStateException("Funding was not applied: " + result);
        }
        return reload(view);
    }

    protected BatchView closeNow() {
        return batches.close(PARTNER, Instant.now()).orElseThrow(() -> new AssertionError("expected a batch to be closed"));
    }

    /** A statement that agrees with our batch exactly, one row per line, as the partner would send it. */
    protected String exactStatement(BatchView batch) {
        return statementOf(batches.lines(batch.id()), 0);
    }

    protected String statementOf(List<BatchLineView> lines, long amountChange) {
        List<String> rows = new ArrayList<>();
        rows.add("reference,amount,currency,date");
        for (BatchLineView line : lines) {
            String reference = line.reference().startsWith("refund:") ? line.reference().substring("refund:".length()) : line.reference();
            rows.add(reference + "," + (-line.amountMinor() + amountChange) + "," + line.currency() + ","
                    + line.postedAt().atOffset(ZoneOffset.UTC).toLocalDate());
        }
        return String.join("\n", rows) + "\n";
    }

    protected static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    protected long countRows(String sql, Object... args) {
        Long count = jdbc.queryForObject(sql, Long.class, args);
        return count == null ? 0 : count;
    }
}
