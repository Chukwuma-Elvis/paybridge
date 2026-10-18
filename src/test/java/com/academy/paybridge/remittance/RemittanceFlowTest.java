package com.academy.paybridge.remittance;

import com.academy.paybridge.account.api.SystemAccount;
import com.academy.paybridge.remittance.api.RemittanceStatus;
import com.academy.paybridge.remittance.api.RemittanceView;
import com.academy.paybridge.transfer.api.PayoutApi;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Step 22: USD in, naira out, and every way it can go wrong. After EVERY test the base class checks that each currency still sums
 * to zero; the tests also check the specific accounts that should have moved (or been put back).
 */
class RemittanceFlowTest extends AbstractRemittanceTest {

    private static final String PAYSTACK_SECRET = "test-paystack-secret-not-a-real-key";

    @Autowired PayoutApi payouts;

    /** Takes a remittance to FUNDED (the partner has confirmed), using the real webhook. */
    private RemittanceView funded(RemittanceView remittance) throws Exception {
        assertThat(fundingWebhook(remittance, SELL)).isEqualTo("APPLIED");
        return reload(remittance);
    }

    private static String payoutOf(RemittanceView view) {
        return "PO-" + view.reference();
    }

    /** All the balances a remittance can touch, so a test can say "everything is back where it started". */
    private record Snapshot(long clearingUsd, long clearingNgn, long positionUsd, long positionNgn, long feesUsd, long partner,
                            long suspense, long paystack) {
    }

    private Snapshot snapshot() {
        return new Snapshot(systemBalance(SystemAccount.REMITTANCE_CLEARING_USD), systemBalance(SystemAccount.REMITTANCE_CLEARING_NGN),
                systemBalance(SystemAccount.FX_POSITION_USD), systemBalance(SystemAccount.FX_POSITION_NGN),
                accounts.getAccount(accounts.ensureSystemAccount("FEES_USD", "USD")).balanceMinor(), partnerBalance(),
                systemBalance(SystemAccount.SUSPENSE), systemBalance(SystemAccount.PAYSTACK_SETTLEMENT));
    }

    // ---- paying a PayBridge customer

    @Test
    void aFundedRemittanceIsConvertedAndPaidToAPayBridgeCustomer() throws Exception {
        String account = newAccountNumber();
        RemittanceView view = createClean(paybridgeRecipient(account));
        Snapshot before = snapshot();
        funded(view);

        runEngine();

        assertThat(statusOf(view)).isEqualTo(RemittanceStatus.PAID);
        assertThat(balanceOfNumber(account)).isEqualTo(BUY);
        Snapshot after = snapshot();
        assertThat(after.clearingUsd() - before.clearingUsd()).as("dollars passed through clearing").isZero();
        assertThat(after.clearingNgn() - before.clearingNgn()).as("naira passed through clearing").isZero();
        assertThat(after.positionUsd() - before.positionUsd()).as("the house is long dollars").isEqualTo(SELL - 500);
        assertThat(after.positionNgn() - before.positionNgn()).as("and short naira").isEqualTo(-BUY);
        assertThat(after.feesUsd() - before.feesUsd()).isEqualTo(500);
        assertThat(after.partner() - before.partner()).as("the partner holds the sender's dollars for us").isEqualTo(-SELL);
        assertThat(audit.history(view.id())).containsExactly("- -> CREATED", "CREATED -> SCREENING", "SCREENING -> AWAITING_FUNDING",
                "AWAITING_FUNDING -> FUNDED", "FUNDED -> CONVERTED", "CONVERTED -> PAID");
    }

    @Test
    void theQuoteIsUsedExactlyOnceNoMatterHowOftenTheEngineRuns() throws Exception {
        RemittanceView view = createClean(paybridgeRecipient(newAccountNumber()));
        funded(view);

        for (int i = 0; i < 5; i++) {
            processor.runOnce();
        }

        assertThat(jdbc.queryForObject("select count(*) from fx_quote where consumed_by = ?", Integer.class, view.id())).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from fx_conversion where quote_id = ?", Integer.class, view.quoteId())).isEqualTo(1);
        assertThat(entryCount("REMITTANCE_PAYOUT", view.reference())).isEqualTo(1);
    }

    @Test
    void aRemittanceThatIsNotFundedIsNeverTouchedByTheEngine() throws Exception {
        RemittanceView waiting = createClean(paybridgeRecipient(newAccountNumber()));
        RemittanceView review = create(command(newQuote(SELL), "Alina Samplova", "NG", paybridgeRecipient(newAccountNumber()))).remittance();

        runEngine();

        assertThat(statusOf(waiting)).isEqualTo(RemittanceStatus.AWAITING_FUNDING);
        assertThat(statusOf(review)).isEqualTo(RemittanceStatus.UNDER_REVIEW);
    }

    @Test
    void thePaymentIsDoneInSeparateStepsAndAPartlyDoneRemittanceResumes() throws Exception {
        String account = newAccountNumber();
        RemittanceView view = createClean(paybridgeRecipient(account));
        funded(view);

        processor.step();      // one pass only: as if the process stopped here
        assertThat(statusOf(view)).isEqualTo(RemittanceStatus.CONVERTED);
        assertThat(balanceOfNumber(account)).isZero();

        processor.step();      // a "restarted" engine carries on from the stored status
        assertThat(statusOf(view)).isEqualTo(RemittanceStatus.PAID);
        assertThat(balanceOfNumber(account)).isEqualTo(BUY);
    }

    @Test
    void aConversionPostedJustBeforeACrashIsNotPostedAgain() throws Exception {
        String account = newAccountNumber();
        RemittanceView view = createClean(paybridgeRecipient(account));
        funded(view);
        // as if the engine posted both legs and then died before recording the new status
        fx.convert(view.quoteId(), accounts.systemAccountId(SystemAccount.REMITTANCE_CLEARING_USD),
                accounts.systemAccountId(SystemAccount.REMITTANCE_CLEARING_NGN));
        Snapshot afterConversion = snapshot();

        runEngine();

        assertThat(statusOf(view)).isEqualTo(RemittanceStatus.PAID);
        assertThat(snapshot().positionNgn()).as("the naira leg was not posted a second time").isEqualTo(afterConversion.positionNgn());
        assertThat(jdbc.queryForObject("select count(*) from fx_conversion where quote_id = ?", Integer.class, view.quoteId())).isEqualTo(1);
    }

    // ---- paying someone at another bank

    @Test
    void aRemittanceToAnotherBankIsPaidThroughTheProvider() throws Exception {
        RemittanceView view = createClean(bankRecipient("3333"));
        Snapshot before = snapshot();
        funded(view);

        runEngine();
        assertThat(statusOf(view)).as("waiting for the provider").isEqualTo(RemittanceStatus.PAYOUT_PENDING);
        assertThat(payouts.find(payoutOf(view)).orElseThrow().status()).isEqualTo(PayoutApi.PayoutStatus.PENDING);
        payoutDispatcher.dispatch(payoutOf(view));
        runEngine();

        assertThat(statusOf(view)).isEqualTo(RemittanceStatus.PAID);
        Snapshot after = snapshot();
        assertThat(after.paystack() - before.paystack()).as("the naira went out through the provider").isEqualTo(BUY);
        assertThat(after.suspense() - before.suspense()).isZero();
        assertThat(after.clearingNgn() - before.clearingNgn()).isZero();
    }

    @Test
    void aPayoutTheProviderLeavesPendingIsFinishedByTheReconciler() throws Exception {
        RemittanceView view = createClean(bankRecipient("0000"));
        funded(view);
        runEngine();
        payoutDispatcher.dispatch(payoutOf(view));
        runEngine();
        assertThat(statusOf(view)).isEqualTo(RemittanceStatus.PAYOUT_PENDING);

        payoutDispatcher.reconcile();
        runEngine();

        assertThat(statusOf(view)).isEqualTo(RemittanceStatus.PAID);
    }

    // ---- when the payout fails: the full refund

    @Test
    void aPayoutTheProviderRefusesIsRefundedInFull() throws Exception {
        RemittanceView view = createClean(bankRecipient("1111"));
        Snapshot before = snapshot();
        funded(view);
        runEngine();
        payoutDispatcher.dispatch(payoutOf(view));

        runEngine();

        assertThat(statusOf(view)).isEqualTo(RemittanceStatus.REFUNDED);
        Snapshot after = snapshot();
        assertThat(after).as("every balance is back exactly where it started").isEqualTo(before);
        assertThat(audit.history(view.id())).contains("CONVERTED -> PAYOUT_PENDING");
        assertThat(audit.history(view.id())).anyMatch(h -> h.startsWith("PAYOUT_PENDING -> REFUND_PENDING"));
        assertThat(audit.history(view.id()).get(audit.history(view.id()).size() - 1)).startsWith("REFUND_PENDING -> REFUNDED");
    }

    @Test
    void aPayoutTheProviderLaterFailsIsRefundedToo() throws Exception {
        RemittanceView view = createClean(bankRecipient("4444"));
        Snapshot before = snapshot();
        funded(view);
        runEngine();
        payoutDispatcher.dispatch(payoutOf(view));
        payoutDispatcher.reconcile();

        runEngine();

        assertThat(statusOf(view)).isEqualTo(RemittanceStatus.REFUNDED);
        assertThat(snapshot()).isEqualTo(before);
    }

    @Test
    void refundingGivesTheSendersAllowanceBack() throws Exception {
        RemittanceView view = createClean(bankRecipient("1111"));
        funded(view);
        runEngine();
        payoutDispatcher.dispatch(payoutOf(view));
        runEngine();

        assertThat(jdbc.queryForObject("select limits_reserved from remittance where id = ?", Boolean.class, view.id())).isFalse();
    }

    @Test
    void aPayoutSuccessThatArrivesAfterTheRefundGoesToManualReviewAndChangesNothing() throws Exception {
        RemittanceView view = createClean(bankRecipient("1111"));
        funded(view);
        runEngine();
        payoutDispatcher.dispatch(payoutOf(view));
        runEngine();
        assertThat(statusOf(view)).isEqualTo(RemittanceStatus.REFUNDED);
        Snapshot refunded = snapshot();
        long id = System.nanoTime();
        byte[] body = ("{\"event\":\"transfer.success\",\"data\":{\"id\":%d,\"reference\":\"%s\",\"amount\":%d,\"currency\":\"NGN\"}}"
                .formatted(id, payoutOf(view), BUY)).getBytes(StandardCharsets.UTF_8);

        String json = mockMvc.perform(post("/api/webhooks/paystack")
                        .header("x-paystack-signature", com.academy.paybridge.transfer.client.Hmac.hmacSha512Hex(PAYSTACK_SECRET, body))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn().getResponse().getContentAsString();

        assertThat(json).contains("MANUAL_REVIEW");
        assertThat(statusOf(view)).isEqualTo(RemittanceStatus.REFUNDED);
        assertThat(snapshot()).isEqualTo(refunded);
    }

    @Test
    void aRecipientAccountThatBecomesUnusableBeforePayoutLeadsToARefund() throws Exception {
        String account = newAccountNumber();
        RemittanceView view = createClean(paybridgeRecipient(account));
        Snapshot before = snapshot();
        funded(view);
        processor.step();     // converted
        jdbc.update("update account set status = 'FROZEN' where account_number = ?", account);

        runEngine();

        assertThat(statusOf(view)).isEqualTo(RemittanceStatus.REFUNDED);
        assertThat(balanceOfNumber(account)).isZero();
        assertThat(snapshot()).isEqualTo(before);
    }

    @Test
    void aStepThatKeepsFailingIsRetriedAndThenRefundedRatherThanStuck() throws Exception {
        RemittanceView view = createClean(paybridgeRecipient(newAccountNumber()));
        Snapshot before = snapshot();
        funded(view);
        UUID clearingNgn = accounts.systemAccountId(SystemAccount.REMITTANCE_CLEARING_NGN);
        jdbc.update("update account set status = 'FROZEN' where id = ?", clearingNgn);   // the naira leg cannot post
        try {
            for (int i = 0; i < 5; i++) {
                processor.step();
            }
            assertThat(statusOf(view)).as("after five failed tries the funded money is not left stranded")
                    .isEqualTo(RemittanceStatus.REFUND_PENDING);
        } finally {
            jdbc.update("update account set status = 'ACTIVE' where id = ?", clearingNgn);
        }

        runEngine();

        assertThat(statusOf(view)).isEqualTo(RemittanceStatus.REFUNDED);
        assertThat(snapshot()).isEqualTo(before);
    }

    @Test
    void aFailureLeavesNoHalfDoneConversionBehind() throws Exception {
        RemittanceView view = createClean(paybridgeRecipient(newAccountNumber()));
        funded(view);
        UUID clearingNgn = accounts.systemAccountId(SystemAccount.REMITTANCE_CLEARING_NGN);
        jdbc.update("update account set status = 'FROZEN' where id = ?", clearingNgn);
        try {
            processor.step();
        } finally {
            jdbc.update("update account set status = 'ACTIVE' where id = ?", clearingNgn);
        }

        assertThat(statusOf(view)).isEqualTo(RemittanceStatus.FUNDED);
        assertThat(jdbc.queryForObject("select count(*) from fx_conversion where quote_id = ?", Integer.class, view.quoteId())).isZero();
        assertThat(entryCount("FX_CONVERSION_USD", "CONV-" + jdbc.queryForObject("select reference from fx_quote where id = ?",
                String.class, view.quoteId()))).isZero();
    }
}
