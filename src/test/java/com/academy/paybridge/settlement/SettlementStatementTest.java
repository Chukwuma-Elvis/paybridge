package com.academy.paybridge.settlement;

import com.academy.paybridge.settlement.service.SettlementViews.BatchView;
import com.academy.paybridge.settlement.service.SettlementViews.BreakView;
import com.academy.paybridge.settlement.service.SettlementViews.StatementResult;
import com.academy.paybridge.shared.exception.ErrorCode;
import com.academy.paybridge.shared.exception.PayBridgeException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Step 25: a statement can be loaded exactly once, and every line on both sides is accounted for. */
class SettlementStatementTest extends AbstractSettlementTest {

    private static void assertCode(Runnable action, ErrorCode expected) {
        assertThatThrownBy(action::run).isInstanceOf(PayBridgeException.class)
                .extracting(e -> ((PayBridgeException) e).getErrorCode()).isEqualTo(expected);
    }

    private BatchView batchOfThree() throws Exception {
        funded(100_000);
        funded(250_000);
        funded(40_000);
        return closeNow();
    }

    private List<BreakView> openBreaks(BatchView batch) {
        return breaks.list(batch.id(), "OPEN");
    }

    @Test
    void anExactStatementMatchesEveryLineAndOpensNoBreak() throws Exception {
        BatchView batch = batchOfThree();

        StatementResult result = statements.upload(batch.id(), bytes(exactStatement(batch)), "tester");

        assertThat(result.duplicate()).isFalse();
        assertThat(result.lineCount()).isEqualTo(3);
        assertThat(result.matched()).isEqualTo(3);
        assertThat(result.breaksOpen()).isZero();
    }

    @Test
    void aMissingLineIsABreakOnOurSide() throws Exception {
        BatchView batch = batchOfThree();
        String full = exactStatement(batch);
        String withoutLastRow = full.substring(0, full.trim().lastIndexOf('\n')) + "\n";

        StatementResult result = statements.upload(batch.id(), bytes(withoutLastRow), "tester");

        assertThat(result.matched()).isEqualTo(2);
        assertThat(openBreaks(batch)).singleElement().satisfies(b -> {
            assertThat(b.type()).isEqualTo("UNMATCHED_OURS");
            assertThat(b.theirAmountMinor()).isNull();
            assertThat(b.reason()).contains("does not show it");
        });
    }

    @Test
    void anExtraLineIsABreakOnTheirSide() throws Exception {
        BatchView batch = batchOfThree();

        statements.upload(batch.id(), bytes(exactStatement(batch) + "RM-NOTOURS,70000,USD,2026-10-14\n"), "tester");

        assertThat(openBreaks(batch)).singleElement().satisfies(b -> {
            assertThat(b.type()).isEqualTo("UNMATCHED_THEIRS");
            assertThat(b.ourAmountMinor()).isNull();
            assertThat(b.theirAmountMinor()).isEqualTo(70_000);
        });
    }

    @Test
    void aDifferentAmountIsADifferenceNotTwoUnmatchedLines() throws Exception {
        BatchView batch = batchOfThree();
        String statement = exactStatement(batch).replaceFirst(",100000,", ",99000,");

        StatementResult result = statements.upload(batch.id(), bytes(statement), "tester");

        assertThat(result.amountDiffers()).isEqualTo(1);
        assertThat(openBreaks(batch)).singleElement().satisfies(b -> {
            assertThat(b.type()).isEqualTo("AMOUNT_DIFFERS");
            assertThat(b.ourAmountMinor()).isEqualTo(100_000);
            assertThat(b.theirAmountMinor()).isEqualTo(99_000);
        });
    }

    @Test
    void aDifferenceInsideTheToleranceIsNotABreakButOneJustOutsideIs() throws Exception {
        BatchView inside = batchOfThree();
        statements.upload(inside.id(), bytes(statementOf(batches.lines(inside.id()), 1)), "tester");
        assertThat(openBreaks(inside)).as("one cent off is within the tolerance").isEmpty();

        BatchView outside = batchOfThree();
        statements.upload(outside.id(), bytes(statementOf(batches.lines(outside.id()), 2)), "tester");
        assertThat(openBreaks(outside)).as("two cents off is not").hasSize(3);
    }

    @Test
    void aDuplicatedStatementLineIsABreak() throws Exception {
        funded(100_000);
        BatchView batch = closeNow();
        String once = exactStatement(batch);
        String twice = once + once.substring(once.indexOf('\n') + 1);

        statements.upload(batch.id(), bytes(twice), "tester");

        assertThat(openBreaks(batch)).singleElement().satisfies(b -> {
            assertThat(b.type()).isEqualTo("UNMATCHED_THEIRS");
            assertThat(b.reason()).contains("Duplicate");
        });
    }

    @Test
    void linesWithoutAReferenceAreMatchedByAmountAndDate() throws Exception {
        funded(123_456);
        BatchView batch = closeNow();
        var line = batches.lines(batch.id()).get(0);
        String date = line.postedAt().atOffset(java.time.ZoneOffset.UTC).toLocalDate().toString();

        StatementResult result = statements.upload(batch.id(), bytes("reference,amount,currency,date\n,123456,USD," + date + "\n"), "tester");

        assertThat(result.matched()).isEqualTo(1);
        assertThat(result.breaksOpen()).isZero();
    }

    @Test
    void oneTotalCanCoverSeveralOfOurLines() throws Exception {
        funded(10_000);
        funded(20_000);
        funded(30_000);
        BatchView batch = closeNow();
        String date = batches.lines(batch.id()).get(0).postedAt().atOffset(java.time.ZoneOffset.UTC).toLocalDate().toString();

        StatementResult result = statements.upload(batch.id(), bytes("reference,amount,currency,date\n,60000,USD," + date + "\n"), "tester");

        assertThat(result.matched()).isEqualTo(1);
        assertThat(result.breaksOpen()).as("all three of our lines are accounted for").isZero();
    }

    // ---- loading rules

    @Test
    void theSameFileTwiceIsANoOp() throws Exception {
        BatchView batch = batchOfThree();
        byte[] file = bytes(exactStatement(batch) + "RM-NOTOURS,70000,USD,2026-10-14\n");

        StatementResult first = statements.upload(batch.id(), file, "tester");
        StatementResult again = statements.upload(batch.id(), file, "tester");

        assertThat(first.duplicate()).isFalse();
        assertThat(again.duplicate()).isTrue();
        assertThat(again.statementId()).isEqualTo(first.statementId());
        assertThat(countRows("select count(*) from settlement_statement where batch_id = ?", batch.id())).isEqualTo(1);
        assertThat(countRows("select count(*) from settlement_break where batch_id = ?", batch.id())).isEqualTo(1);
    }

    @Test
    void aDifferentFileForTheSameBatchIsRefused() throws Exception {
        BatchView batch = batchOfThree();
        statements.upload(batch.id(), bytes(exactStatement(batch)), "tester");

        assertCode(() -> statements.upload(batch.id(), bytes(exactStatement(batch) + "RM-X,1,USD,2026-10-14\n"), "tester"), ErrorCode.CONFLICT);
    }

    @Test
    void aMalformedFileReportsTheRowsAndImportsNothing() throws Exception {
        BatchView batch = batchOfThree();
        String bad = exactStatement(batch) + "RM-BAD,12.50,USD,2026-10-14\nRM-BAD2,5,USD,not-a-date\n";

        assertThatThrownBy(() -> statements.upload(batch.id(), bytes(bad), "tester"))
                .isInstanceOf(PayBridgeException.class)
                .hasMessageContaining("Row 5").hasMessageContaining("Row 6").hasMessageContaining("not imported");

        assertThat(countRows("select count(*) from settlement_statement where batch_id = ?", batch.id())).isZero();
        assertThat(countRows("select count(*) from settlement_break where batch_id = ?", batch.id())).isZero();
    }

    @Test
    void anEmptyOrOversizedFileIsRefused() throws Exception {
        BatchView batch = batchOfThree();

        assertCode(() -> statements.upload(batch.id(), new byte[0], "tester"), ErrorCode.VALIDATION_FAILED);
        assertCode(() -> statements.upload(batch.id(), new byte[1_048_577], "tester"), ErrorCode.PAYLOAD_TOO_LARGE);
        assertCode(() -> statements.upload(UUID.randomUUID(), bytes("reference,amount,currency,date\nA,1,USD,2026-10-14\n"), "tester"),
                ErrorCode.RESOURCE_NOT_FOUND);
    }

    @Test
    void aStatementInTheWrongCurrencyIsRefused() throws Exception {
        BatchView batch = batchOfThree();

        assertThatThrownBy(() -> statements.upload(batch.id(), bytes("reference,amount,currency,date\nRM-1,5,NGN,2026-10-14\n"), "tester"))
                .hasMessageContaining("currency must be USD");
    }
}
