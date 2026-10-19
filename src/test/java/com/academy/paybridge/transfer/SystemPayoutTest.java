package com.academy.paybridge.transfer;

import com.academy.paybridge.LedgerInvariants;
import com.academy.paybridge.account.api.AccountApi;
import com.academy.paybridge.account.api.SystemAccount;
import com.academy.paybridge.security.AbstractSecurityTest;
import com.academy.paybridge.shared.exception.ErrorCode;
import com.academy.paybridge.shared.exception.PayBridgeException;
import com.academy.paybridge.transfer.api.PayoutApi;
import com.academy.paybridge.transfer.api.PayoutApi.PayoutRequest;
import com.academy.paybridge.transfer.api.PayoutApi.PayoutStatus;
import com.academy.paybridge.transfer.client.Hmac;
import com.academy.paybridge.transfer.service.SystemPayoutDispatcher;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * System-initiated payouts: the same hold, send, settle-or-release shape as an external transfer, for money that comes from one of
 * the bank's own accounts. The fake bank's behaviour is chosen by the last four digits of the account number, as for transfers.
 */
class SystemPayoutTest extends AbstractSecurityTest {

    private static final String PAYSTACK_SECRET = "test-paystack-secret-not-a-real-key";
    private static final long AMOUNT = 250_000;

    @Autowired PayoutApi payouts;
    @Autowired SystemPayoutDispatcher dispatcher;
    @Autowired AccountApi accounts;
    @Autowired JdbcTemplate jdbc;

    @AfterEach
    void everyCurrencyStillSumsToZero() {
        LedgerInvariants.everyCurrencySumsToZero(jdbc);
    }

    private UUID newSource() {
        return accounts.ensureSystemAccount("T-PAYSRC-" + UUID.randomUUID().toString().substring(0, 8), "NGN");
    }

    private static String newReference() {
        return "PO-" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
    }

    private static String accountEndingIn(String lastFour) {
        return "000000" + lastFour;
    }

    private PayoutRequest request(String reference, UUID source, long amount, String lastFour) {
        return new PayoutRequest(reference, source, amount, "058", accountEndingIn(lastFour), "Ada Obi", "remittance");
    }

    private long balance(UUID account) {
        return accounts.getAccount(account).balanceMinor();
    }

    private long system(SystemAccount account) {
        return balance(accounts.systemAccountId(account));
    }

    /**
     * The reconciler only asks about payouts that have been quiet a little while, comparing the app's clock with the database's.
     * Those two clocks can differ by a moment (the database runs in a container), so a test makes the payouts clearly old first.
     */
    private void reconcileNow() {
        jdbc.update("update system_payout set updated_at = now() - interval '1 minute' where status = 'PROCESSING'");
        dispatcher.reconcile();
    }

    private PayoutStatus statusOf(String reference) {
        return payouts.find(reference).orElseThrow().status();
    }

    // ---- accepting the request

    @Test
    void requestingAPayoutParksTheMoneyInSuspense() {
        UUID source = newSource();
        long suspense = system(SystemAccount.SUSPENSE);

        PayoutApi.PayoutView view = payouts.request(request(newReference(), source, AMOUNT, "3333"));

        assertThat(view.status()).isEqualTo(PayoutStatus.PENDING);
        assertThat(balance(source)).isEqualTo(-AMOUNT);
        assertThat(system(SystemAccount.SUSPENSE)).isEqualTo(suspense + AMOUNT);
    }

    @Test
    void theSameRequestTwiceHoldsTheMoneyOnce() {
        UUID source = newSource();
        String reference = newReference();
        payouts.request(request(reference, source, AMOUNT, "3333"));

        PayoutApi.PayoutView again = payouts.request(request(reference, source, AMOUNT, "3333"));

        assertThat(again.reference()).isEqualTo(reference);
        assertThat(balance(source)).isEqualTo(-AMOUNT);
    }

    @Test
    void theSameReferenceWithDifferentDetailsIsRefused() {
        UUID source = newSource();
        String reference = newReference();
        payouts.request(request(reference, source, AMOUNT, "3333"));

        assertThatThrownBy(() -> payouts.request(request(reference, source, AMOUNT + 1, "3333")))
                .isInstanceOf(PayBridgeException.class)
                .extracting(e -> ((PayBridgeException) e).getErrorCode()).isEqualTo(ErrorCode.CONFLICT);
        assertThat(balance(source)).isEqualTo(-AMOUNT);
    }

    @Test
    void badInputIsRefusedBeforeAnyMoneyMoves() {
        UUID source = newSource();
        PayoutRequest noPrefix = new PayoutRequest("XX-12345678", source, AMOUNT, "058", accountEndingIn("3333"), "Ada", null);
        PayoutRequest badAccount = new PayoutRequest(newReference(), source, AMOUNT, "058", "12345", "Ada", null);
        PayoutRequest zero = new PayoutRequest(newReference(), source, 0, "058", accountEndingIn("3333"), "Ada", null);

        for (PayoutRequest bad : new PayoutRequest[]{noPrefix, badAccount, zero}) {
            assertThatThrownBy(() -> payouts.request(bad)).isInstanceOf(PayBridgeException.class)
                    .extracting(e -> ((PayBridgeException) e).getErrorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
        }
        assertThat(balance(source)).isZero();
    }

    // ---- the provider's answers

    @Test
    void whenTheBankPaysAtOnceTheHoldBecomesASettlement() {
        UUID source = newSource();
        String reference = newReference();
        long suspense = system(SystemAccount.SUSPENSE);
        long paystack = system(SystemAccount.PAYSTACK_SETTLEMENT);
        payouts.request(request(reference, source, AMOUNT, "3333"));

        dispatcher.dispatch(reference);

        assertThat(statusOf(reference)).isEqualTo(PayoutStatus.SUCCESS);
        assertThat(system(SystemAccount.SUSPENSE)).isEqualTo(suspense);
        assertThat(system(SystemAccount.PAYSTACK_SETTLEMENT)).isEqualTo(paystack + AMOUNT);
        assertThat(balance(source)).isEqualTo(-AMOUNT);
    }

    @Test
    void whenTheBankRefusesTheMoneyGoesBackToTheSource() {
        UUID source = newSource();
        String reference = newReference();
        payouts.request(request(reference, source, AMOUNT, "1111"));

        dispatcher.dispatch(reference);

        assertThat(statusOf(reference)).isEqualTo(PayoutStatus.FAILED);
        assertThat(balance(source)).isZero();
        assertThat(payouts.find(reference).orElseThrow().failureReason()).isNotBlank();
    }

    @Test
    void aPendingPayoutIsFinishedByTheReconciler() {
        UUID source = newSource();
        String reference = newReference();
        payouts.request(request(reference, source, AMOUNT, "0000"));
        dispatcher.dispatch(reference);
        assertThat(statusOf(reference)).isEqualTo(PayoutStatus.PROCESSING);

        reconcileNow();

        assertThat(statusOf(reference)).isEqualTo(PayoutStatus.SUCCESS);
    }

    @Test
    void aPayoutTheBankLaterFailsIsRefundedByTheReconciler() {
        UUID source = newSource();
        String reference = newReference();
        payouts.request(request(reference, source, AMOUNT, "4444"));
        dispatcher.dispatch(reference);
        assertThat(balance(source)).as("still held").isEqualTo(-AMOUNT);

        reconcileNow();

        assertThat(statusOf(reference)).isEqualTo(PayoutStatus.FAILED);
        assertThat(balance(source)).isZero();
    }

    @Test
    void aTimeoutIsNeverTreatedAsAFailureAndTheRetryDoesNotDoubleSend() {
        UUID source = newSource();
        String reference = newReference();
        payouts.request(request(reference, source, AMOUNT, "5555"));

        dispatcher.dispatch(reference);   // the provider got it but the answer was lost
        assertThat(statusOf(reference)).isEqualTo(PayoutStatus.PROCESSING);
        assertThat(balance(source)).as("money stays held").isEqualTo(-AMOUNT);
        dispatcher.dispatch(reference);   // the retry is the same reference, so the provider does not send twice

        reconcileNow();
        assertThat(statusOf(reference)).isEqualTo(PayoutStatus.SUCCESS);
    }

    @Test
    void aPayoutThatNeverReachedTheBankIsReleasedAfterTooManyTimeouts() {
        UUID source = newSource();
        String reference = newReference();
        payouts.request(request(reference, source, AMOUNT, "2222"));

        for (int i = 0; i < 4; i++) {
            dispatcher.dispatch(reference);
        }
        assertThat(statusOf(reference)).isEqualTo(PayoutStatus.PROCESSING);
        reconcileNow();

        assertThat(statusOf(reference)).isEqualTo(PayoutStatus.FAILED);
        assertThat(balance(source)).isZero();
    }

    // ---- webhooks

    private static MockHttpServletRequestBuilder paystack(String event, String reference, long amount, String currency) {
        long id = ThreadLocalRandom.current().nextLong(1_000_000, 900_000_000);
        byte[] body = ("{\"event\":\"%s\",\"data\":{\"id\":%d,\"reference\":\"%s\",\"amount\":%d,\"currency\":\"%s\",\"transfer_code\":\"TRF_%d\"}}"
                .formatted(event, id, reference, amount, currency, id)).getBytes(StandardCharsets.UTF_8);
        return post("/api/webhooks/paystack").header("x-paystack-signature", Hmac.hmacSha512Hex(PAYSTACK_SECRET, body))
                .contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private String webhook(MockHttpServletRequestBuilder request) throws Exception {
        String json = mockMvc.perform(request).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(json, "$.result");
    }

    @Test
    void aSuccessWebhookFinishesAPayoutTheBankLeftPending() throws Exception {
        UUID source = newSource();
        String reference = newReference();
        payouts.request(request(reference, source, AMOUNT, "6666"));
        dispatcher.dispatch(reference);

        assertThat(webhook(paystack("transfer.success", reference, AMOUNT, "NGN"))).isEqualTo("APPLIED");

        assertThat(statusOf(reference)).isEqualTo(PayoutStatus.SUCCESS);
    }

    @Test
    void paystackSendsReferencesBackInLowercaseAndTheyStillMatch() throws Exception {
        UUID source = newSource();
        String reference = "PO-RM-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase();
        payouts.request(request(reference, source, AMOUNT, "6666"));
        dispatcher.dispatch(reference);

        assertThat(webhook(paystack("transfer.success", reference.toLowerCase(), AMOUNT, "NGN"))).isEqualTo("APPLIED");

        assertThat(statusOf(reference)).isEqualTo(PayoutStatus.SUCCESS);
    }

    @Test
    void aWebhookWithTheWrongAmountIsNotApplied() throws Exception {
        UUID source = newSource();
        String reference = newReference();
        payouts.request(request(reference, source, AMOUNT, "6666"));
        dispatcher.dispatch(reference);

        assertThat(webhook(paystack("transfer.success", reference, AMOUNT - 1, "NGN"))).isEqualTo("AMOUNT_MISMATCH");

        assertThat(statusOf(reference)).isEqualTo(PayoutStatus.PROCESSING);
    }

    @Test
    void aSuccessThatArrivesAfterTheRefundGoesToManualReview() throws Exception {
        UUID source = newSource();
        String reference = newReference();
        payouts.request(request(reference, source, AMOUNT, "1111"));
        dispatcher.dispatch(reference);
        assertThat(statusOf(reference)).isEqualTo(PayoutStatus.FAILED);

        assertThat(webhook(paystack("transfer.success", reference, AMOUNT, "NGN"))).isEqualTo("MANUAL_REVIEW");

        assertThat(statusOf(reference)).isEqualTo(PayoutStatus.FAILED);
        assertThat(balance(source)).as("the refund stands").isZero();
    }

    @Test
    void aWebhookForAnUnknownPayoutIsUnmatched() throws Exception {
        assertThat(webhook(paystack("transfer.success", newReference(), AMOUNT, "NGN"))).isEqualTo("UNMATCHED");
    }
}
