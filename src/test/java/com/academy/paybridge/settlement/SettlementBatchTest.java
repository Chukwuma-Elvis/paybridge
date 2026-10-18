package com.academy.paybridge.settlement;

import com.academy.paybridge.remittance.api.RemittanceStatus;
import com.academy.paybridge.remittance.api.RemittanceView;
import com.academy.paybridge.settlement.service.SettlementViews.BatchLineView;
import com.academy.paybridge.settlement.service.SettlementViews.BatchView;
import com.academy.paybridge.shared.exception.ErrorCode;
import com.academy.paybridge.shared.exception.PayBridgeException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Step 24: batches freeze slices of the partner's account, every line lands in exactly one, and paying clears the net. */
class SettlementBatchTest extends AbstractSettlementTest {

    private static void assertCode(Runnable action, ErrorCode expected) {
        assertThatThrownBy(action::run).isInstanceOf(PayBridgeException.class)
                .extracting(e -> ((PayBridgeException) e).getErrorCode()).isEqualTo(expected);
    }

    // ---- closing

    @Test
    void aBatchFreezesTheMovementsAndTheNetEqualsTheirSum() throws Exception {
        funded(100_000);
        funded(250_000);
        funded(40_000);

        BatchView batch = closeNow();

        assertThat(batch.status()).isEqualTo("CLOSED");
        assertThat(batch.lineCount()).isEqualTo(3);
        assertThat(batch.movementsMinor()).as("the partner holds the money for us, so the net is negative").isEqualTo(-390_000);
        assertThat(batch.closingMinor()).isEqualTo(batch.openingMinor() + batch.movementsMinor());
        assertThat(batches.lines(batch.id())).extracting(BatchLineView::amountMinor).containsExactlyInAnyOrder(-100_000L, -250_000L, -40_000L);
        assertThat(batches.lines(batch.id())).extracting(BatchLineView::entryType).containsOnly("REMITTANCE_FUNDING");
    }

    @Test
    void closingTheSameCutOffTwiceGivesTheSameBatchAndChangesNothing() throws Exception {
        funded(100_000);
        Instant cutoff = Instant.now();

        BatchView first = batches.close(PARTNER, cutoff).orElseThrow();
        funded(50_000);                                         // a later movement must not leak into the earlier cut-off
        BatchView second = batches.close(PARTNER, cutoff).orElseThrow();

        assertThat(second.id()).isEqualTo(first.id());
        assertThat(second.lineCount()).isEqualTo(1);
        assertThat(batches.lines(first.id())).hasSize(1);
    }

    @Test
    void movementsAfterTheCutOffGoToTheNextBatch() throws Exception {
        funded(100_000);
        BatchView first = closeNow();
        funded(70_000);

        BatchView second = closeNow();

        assertThat(second.sequence()).isEqualTo(first.sequence() + 1);
        assertThat(second.lineCount()).isEqualTo(1);
        assertThat(second.movementsMinor()).isEqualTo(-70_000);
        assertThat(second.openingMinor()).as("it carries on from where the last batch ended").isEqualTo(first.closingMinor());
    }

    @Test
    void aSecondCloseWithNothingNewMakesNoBatch() throws Exception {
        funded(100_000);
        closeNow();

        assertThat(batches.close(PARTNER, Instant.now())).isEmpty();
    }

    @Test
    void anUnknownPartnerIsRefused() {
        assertCode(() -> batches.close("nobody", Instant.now()), ErrorCode.RESOURCE_NOT_FOUND);
        assertCode(() -> batches.position("nobody"), ErrorCode.RESOURCE_NOT_FOUND);
    }

    @Test
    void theBatchedLinesAreNeverEditedOrDeleted() throws Exception {
        funded(100_000);
        BatchView batch = closeNow();

        assertThatThrownBy(() -> jdbc.update("delete from settlement_batch_line where batch_id = ?", batch.id()))
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("update settlement_batch_line set amount_minor = 1 where batch_id = ?", batch.id()))
                .hasMessageContaining("append-only");
    }

    @Test
    void aLedgerLineCannotBeInTwoBatches() throws Exception {
        funded(100_000);
        BatchView batch = closeNow();
        UUID ledgerLine = batches.lines(batch.id()).get(0).ledgerLineId();

        assertThatThrownBy(() -> jdbc.update("""
                insert into settlement_batch_line (id, batch_id, ledger_line_id, entry_id, entry_type, reference, amount_minor, currency, posted_at)
                select gen_random_uuid(), batch_id, ledger_line_id, entry_id, entry_type, reference, amount_minor, currency, posted_at
                from settlement_batch_line where batch_id = ? limit 1
                """, batch.id())).hasMessageContaining("uq_settlement_batch_line_ledger");
        assertThat(ledgerLine).isNotNull();
    }

    @Test
    void thePositionSplitsWhatIsBatchedFromWhatIsWaiting() throws Exception {
        funded(100_000);
        closeNow();
        funded(30_000);

        var position = batches.position(PARTNER);

        assertThat(position.unbatchedMinor()).isEqualTo(-30_000);
        assertThat(position.unbatchedLines()).isEqualTo(1);
        assertThat(position.accountBalanceMinor()).isEqualTo(-130_000);
    }

    @Test
    void postingsRacingTheClosersNeitherLoseNorDoubleCountALine() throws Exception {
        int count = 6;
        List<RemittanceView> waiting = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            waiting.add(create(command(newQuote(100_000), "Chidi Okafor", "NG", paybridgeRecipient(newAccountNumber()))).remittance());
        }
        ExecutorService pool = Executors.newFixedThreadPool(count + 2);
        CountDownLatch gun = new CountDownLatch(1);
        List<Future<?>> work = new ArrayList<>();
        for (RemittanceView view : waiting) {
            work.add(pool.submit(() -> {
                gun.await();
                return fundingWebhook(view, 100_000);
            }));
        }
        for (int i = 0; i < 2; i++) {
            work.add(pool.submit(() -> {
                gun.await();
                for (int j = 0; j < 8; j++) {
                    batches.close(PARTNER, Instant.now());
                }
                return null;
            }));
        }
        gun.countDown();
        for (Future<?> f : work) {
            f.get(60, TimeUnit.SECONDS);
        }
        pool.shutdown();
        batches.close(PARTNER, Instant.now());      // sweeps whatever was still being committed when the others closed

        for (RemittanceView view : waiting) {
            assertThat(countRows("select count(*) from settlement_batch_line where reference = ?", view.reference()))
                    .as("remittance %s is in exactly one batch", view.reference()).isEqualTo(1);
        }
        assertThat(batches.position(PARTNER).unbatchedLines()).isZero();
    }

    // ---- paying

    @Test
    void confirmingPostsOneSettlementThatBringsThePartnerAccountToZero() throws Exception {
        funded(100_000);
        funded(60_000);
        BatchView batch = closeNow();
        assertThat(partnerBalance()).isEqualTo(-160_000);

        batches.instruct(batch.id());
        BatchView paid = batches.confirm(batch.id());

        assertThat(paid.status()).isEqualTo("CONFIRMED");
        assertThat(partnerBalance()).as("the partner has paid what it held for us").isZero();
        assertThat(entryCount("SETTLEMENT_PAYMENT", "SETTLE-FAKEPARTNER-" + batch.sequence())).isEqualTo(1);
    }

    @Test
    void confirmingTwiceOrInstructingTwiceChangesNothingTheSecondTime() throws Exception {
        funded(100_000);
        BatchView batch = closeNow();
        batches.instruct(batch.id());
        batches.instruct(batch.id());
        batches.confirm(batch.id());
        long balance = partnerBalance();

        BatchView again = batches.confirm(batch.id());

        assertThat(again.status()).isEqualTo("CONFIRMED");
        assertThat(partnerBalance()).isEqualTo(balance);
        assertThat(entryCount("SETTLEMENT_PAYMENT", "SETTLE-FAKEPARTNER-" + batch.sequence())).isEqualTo(1);
    }

    @Test
    void aBatchCannotBePaidBeforeItIsInstructed() throws Exception {
        funded(100_000);
        BatchView batch = closeNow();

        assertCode(() -> batches.confirm(batch.id()), ErrorCode.INVALID_STATE);
        assertThat(batches.get(batch.id()).status()).isEqualTo("CLOSED");
    }

    @Test
    void aFundingAndItsRefundNetToNothingAndNeedNoPayment() throws Exception {
        RemittanceView view = create(command(newQuote(SELL), "Chidi Okafor", "NG", bankRecipient("1111"))).remittance();
        fundingWebhook(view, SELL);
        runEngine();
        payoutDispatcher.dispatch("PO-" + view.reference());
        runEngine();
        assertThat(statusOf(view)).isEqualTo(RemittanceStatus.REFUNDED);

        BatchView batch = closeNow();

        assertThat(batch.lineCount()).isEqualTo(2);
        assertThat(batch.movementsMinor()).isZero();
        batches.instruct(batch.id());
        batches.confirm(batch.id());
        assertThat(entryCount("SETTLEMENT_PAYMENT", "SETTLE-FAKEPARTNER-" + batch.sequence())).isZero();
        assertThat(partnerBalance()).isZero();
    }

    @Test
    void anEarlierPaymentIsNeverPaidAgainByTheNextBatch() throws Exception {
        funded(100_000);
        BatchView first = closeNow();
        batches.instruct(first.id());
        batches.confirm(first.id());          // posts a payment line, which the next batch will carry
        funded(40_000);

        BatchView second = closeNow();

        assertThat(second.lineCount()).as("the new funding and the earlier payment line").isEqualTo(2);
        assertThat(batches.netToSettle(second.id())).as("only the new funding is owed").isEqualTo(-40_000);
        batches.instruct(second.id());
        batches.confirm(second.id());
        assertThat(partnerBalance()).isZero();
    }

    @Test
    void theBatchReportTracesItsFiguresToTheLedger() throws Exception {
        funded(100_000);
        funded(25_000);
        BatchView batch = closeNow();

        var report = batches.report(batch.id());

        assertThat(report.movementsMinor()).isEqualTo(-125_000);
        assertThat(report.netToSettleMinor()).isEqualTo(-125_000);
        assertThat(report.closingMinor()).isEqualTo(report.openingMinor() + report.movementsMinor());
        assertThat(report.tracesToLedger()).as("closing equals every ledger line before the cut-off").isTrue();
        assertThat(report.lineCount()).isEqualTo(2);
        assertThat(report.openBreaks()).isZero();
    }
}
