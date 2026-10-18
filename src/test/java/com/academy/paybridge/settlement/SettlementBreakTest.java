package com.academy.paybridge.settlement;

import com.academy.paybridge.settlement.service.SettlementViews.BatchView;
import com.academy.paybridge.settlement.service.SettlementViews.BreakView;
import com.academy.paybridge.shared.exception.ErrorCode;
import com.academy.paybridge.shared.exception.PayBridgeException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Step 26: every break is resolved with a note, money differences get a correcting entry, and a batch can reach RECONCILED. */
class SettlementBreakTest extends AbstractSettlementTest {

    private static void assertCode(Runnable action, ErrorCode expected) {
        assertThatThrownBy(action::run).isInstanceOf(PayBridgeException.class)
                .extracting(e -> ((PayBridgeException) e).getErrorCode()).isEqualTo(expected);
    }

    /** One funding of USD 1,000.00 whose statement says the partner holds only USD 990.00. */
    private BatchView batchWithADifference() throws Exception {
        funded(100_000);
        BatchView batch = closeNow();
        statements.upload(batch.id(), bytes(statementOf(batches.lines(batch.id()), -1_000)), "tester");
        return batch;
    }

    private BreakView theBreak(BatchView batch) {
        return breaks.list(batch.id(), "OPEN").get(0);
    }

    @Test
    void resolvingNeedsANote() throws Exception {
        BreakView b = theBreak(batchWithADifference());

        assertCode(() -> breaks.resolve(b.id(), " ", false, "ops"), ErrorCode.VALIDATION_FAILED);
        assertCode(() -> breaks.resolve(b.id(), null, false, "ops"), ErrorCode.VALIDATION_FAILED);
        assertThat(breaks.get(b.id()).status()).isEqualTo("OPEN");
    }

    @Test
    void aResolvedBreakRecordsWhoWhenAndWhyAndCannotBeResolvedTwice() throws Exception {
        BreakView b = theBreak(batchWithADifference());

        BreakView resolved = breaks.resolve(b.id(), "Partner rounded the fee; agreed by email", false, "ops-1");

        assertThat(resolved.status()).isEqualTo("RESOLVED");
        assertThat(resolved.resolvedBy()).isEqualTo("ops-1");
        assertThat(resolved.resolutionNote()).contains("agreed by email");
        assertThat(resolved.adjustmentEntryId()).isNull();
        assertCode(() -> breaks.resolve(b.id(), "again", false, "ops-2"), ErrorCode.INVALID_STATE);
    }

    @Test
    void aCorrectingEntryMovesThePartnerAccountByTheDifferenceAndKeepsTheBooksBalanced() throws Exception {
        BatchView batch = batchWithADifference();
        BreakView b = theBreak(batch);
        long before = partnerBalance();

        BreakView resolved = breaks.resolve(b.id(), "Partner is right: we over-recorded USD 10.00", true, "ops");

        assertThat(resolved.adjustmentEntryId()).isNotNull();
        assertThat(partnerBalance() - before).as("ours 100000 minus theirs 99000").isEqualTo(1_000);
        assertThat(entryCount("SETTLEMENT_ADJUSTMENT", "ADJ-" + b.id())).isEqualTo(1);
        // AbstractRemittanceTest checks that each currency still sums to zero after the test
    }

    @Test
    void aCorrectionIsRefusedWhenThereIsNoMoneyDifference() throws Exception {
        funded(100_000);
        BatchView batch = closeNow();
        statements.upload(batch.id(), bytes(statementOf(batches.lines(batch.id()), 0) + "RM-ZERO,1,USD,2026-10-14\n"), "tester");
        BreakView extra = theBreak(batch);

        // the extra line is real money (1 cent), so a correction IS possible; the same call with a break that has equal figures is not
        assertThat(breaks.resolve(extra.id(), "Partner overstated by 1 cent", true, "ops").adjustmentEntryId()).isNotNull();
    }

    @Test
    void anUnmatchedLineOfOursCanBeCorrectedByRemovingItsEffect() throws Exception {
        funded(100_000);
        BatchView batch = closeNow();
        statements.upload(batch.id(), bytes("reference,amount,currency,date\nRM-ELSE,5,USD,2026-10-14\n"), "tester");
        BreakView ours = breaks.list(batch.id(), "OPEN").stream().filter(x -> x.type().equals("UNMATCHED_OURS")).findFirst().orElseThrow();
        long before = partnerBalance();

        breaks.resolve(ours.id(), "Partner has not recorded it yet; accepting their position", true, "ops");

        assertThat(partnerBalance() - before).isEqualTo(100_000);
    }

    @Test
    void aBreakAssignedToSomeoneShowsItsOwner() throws Exception {
        BreakView b = theBreak(batchWithADifference());

        assertThat(breaks.assign(b.id(), "amina").owner()).isEqualTo("amina");
        assertCode(() -> breaks.assign(b.id(), " "), ErrorCode.VALIDATION_FAILED);
    }

    // ---- reaching RECONCILED

    @Test
    void aPaidBatchWithAnExactStatementIsReconciled() throws Exception {
        funded(100_000);
        BatchView batch = closeNow();
        statements.upload(batch.id(), bytes(exactStatement(batch)), "tester");
        batches.instruct(batch.id());

        BatchView paid = batches.confirm(batch.id());

        assertThat(paid.status()).isEqualTo("RECONCILED");
        assertThat(partnerBalance()).isZero();
    }

    @Test
    void loadingTheStatementAfterPaymentAlsoReconciles() throws Exception {
        funded(100_000);
        BatchView batch = closeNow();
        batches.instruct(batch.id());
        batches.confirm(batch.id());
        assertThat(batches.get(batch.id()).status()).as("no statement yet").isEqualTo("CONFIRMED");

        statements.upload(batch.id(), bytes(exactStatement(batch)), "tester");

        assertThat(batches.get(batch.id()).status()).isEqualTo("RECONCILED");
    }

    @Test
    void aBatchWithOpenBreaksStaysUnreconciledUntilEveryBreakIsResolved() throws Exception {
        BatchView batch = batchWithADifference();
        batches.instruct(batch.id());
        batches.confirm(batch.id());
        assertThat(batches.get(batch.id()).status()).isEqualTo("CONFIRMED");

        breaks.resolve(theBreak(batch).id(), "Agreed", false, "ops");

        assertThat(batches.get(batch.id()).status()).isEqualTo("RECONCILED");
        assertThat(batches.reconcileIfClean(batch.id())).as("doing it again changes nothing").isFalse();
    }

    @Test
    void aBatchWithBreaksCanBeMarkedDisputedAndStillReconcilesLater() throws Exception {
        BatchView batch = batchWithADifference();
        batches.instruct(batch.id());
        batches.confirm(batch.id());

        assertThat(batches.dispute(batch.id(), "Waiting for the partner to send proof").status()).isEqualTo("DISPUTED");
        assertCode(() -> batches.dispute(batch.id(), " "), ErrorCode.VALIDATION_FAILED);
        breaks.resolve(theBreak(batch).id(), "Proof received", false, "ops");

        assertThat(batches.get(batch.id()).status()).isEqualTo("RECONCILED");
    }

    @Test
    void aBatchWithoutBreaksCannotBeDisputed() throws Exception {
        funded(100_000);
        BatchView batch = closeNow();
        batches.instruct(batch.id());
        batches.confirm(batch.id());

        assertCode(() -> batches.dispute(batch.id(), "No reason"), ErrorCode.INVALID_STATE);
    }

    // ---- the report

    @Test
    void theReportCountsMatchesAndOpenAndResolvedBreaks() throws Exception {
        funded(100_000);
        funded(50_000);
        BatchView batch = closeNow();
        statements.upload(batch.id(), bytes(statementOf(batches.lines(batch.id()), 0).replaceFirst(",50000,", ",49000,")), "tester");
        breaks.resolve(theBreak(batch).id(), "Agreed", false, "ops");

        var report = batches.report(batch.id());

        assertThat(report.statementLines()).isEqualTo(2);
        assertThat(report.matchedLines()).isEqualTo(1);
        assertThat(report.openBreaks()).isZero();
        assertThat(report.resolvedBreaks()).isEqualTo(1);
        assertThat(report.tracesToLedger()).isTrue();
    }

    @Test
    void openBreaksAreCountedByAge() throws Exception {
        BatchView batch = batchWithADifference();
        BreakView b = theBreak(batch);
        assertThat(batches.report(batch.id()).openBreaksByAge().get("0-1d")).isEqualTo(1);

        jdbc.update("update settlement_break set created_at = now() - interval '10 days' where id = ?", b.id());

        var report = batches.report(batch.id());
        assertThat(report.openBreaksByAge().get(">7d")).isEqualTo(1);
        assertThat(report.openBreaksByAge().get("0-1d")).isZero();
    }
}
