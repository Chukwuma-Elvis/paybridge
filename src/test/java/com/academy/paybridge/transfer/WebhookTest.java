package com.academy.paybridge.transfer;

import com.academy.paybridge.LedgerInvariants;
import com.academy.paybridge.account.api.AccountApi;
import com.academy.paybridge.account.api.SystemAccount;
import com.academy.paybridge.security.AbstractSecurityTest;
import com.academy.paybridge.security.service.RateLimiter;
import com.academy.paybridge.transfer.api.TransferApi;
import com.academy.paybridge.transfer.api.TransferStatus;
import com.academy.paybridge.transfer.client.Hmac;
import com.academy.paybridge.transfer.service.InternalTransferCommand;
import com.academy.paybridge.transfer.service.TransferDispatcher;
import com.academy.paybridge.transfer.service.TransferService;
import com.jayway.jsonpath.JsonPath;
import io.micrometer.core.instrument.Counter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Webhooks, end to end: a signed request arrives, the right thing happens to the transfer and the money.
 * <p>
 * The fake bank's behaviour is chosen by the LAST FOUR DIGITS of the payee's account number. Two of them matter here:
 * <ul>
 *   <li><b>6666</b>: the bank accepts the transfer and then stays "still processing" for ever, so only a webhook can finish it.</li>
 *   <li><b>7777</b>: the bank pays at once; the only way to see it reversed afterwards is a webhook.</li>
 * </ul>
 * Fee for an amount up to NGN 5,000 is NGN 10 (1,000 kobo). The test profile gives both providers a (fake) secret.
 */
class WebhookTest extends AbstractSecurityTest {

    private static final String PAYSTACK_SECRET = "test-paystack-secret-not-a-real-key";
    private static final String MONNIFY_SECRET = "test-monnify-secret-not-a-real-key";

    private static final long AMOUNT = 200_000;   // NGN 2,000
    private static final long FEE = 1_000;        // NGN 10
    private static final long TOTAL = AMOUNT + FEE;

    @Autowired AccountApi accountApi;
    @Autowired TransferApi transferApi;
    @Autowired TransferService transferService;
    @Autowired TransferDispatcher dispatcher;
    @Autowired JdbcTemplate jdbc;

    private record Started(UUID id, String reference) {
    }

    @AfterEach
    void theBankStillAddsUpToZero() {
        LedgerInvariants.everyCurrencySumsToZero(jdbc);
    }

    // ------------------------------------------------------------------ helpers

    private long balance(UUID accountId) {
        return accountApi.getAccount(accountId).balanceMinor();
    }

    private long systemBalance(SystemAccount account) {
        return balance(accountApi.systemAccountId(account));
    }

    private TransferStatus statusOf(UUID transferId) {
        return transferApi.getTransfer(transferId).status();
    }

    private UUID customerOf(UUID accountId) {
        return accountApi.getAccount(accountId).ownerCustomerId();
    }

    private UUID beneficiary(UUID customerId, String endingDigits) throws Exception {
        String accountNumber = String.format("%06d", ThreadLocalRandom.current().nextInt(1_000_000)) + endingDigits;
        String json = mockMvc.perform(post("/api/beneficiaries")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customerId\": \"%s\", \"bankCode\": \"058\", \"accountNumber\": \"%s\"}"
                                .formatted(customerId, accountNumber)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(json, "$.id"));
    }

    /** Asks for an external transfer, then runs the dispatcher once so the (fake) bank has been contacted. */
    private Started startAndDispatch(UUID source, String endingDigits, long amountMinor) throws Exception {
        UUID beneficiary = beneficiary(customerOf(source), endingDigits);
        String json = mockMvc.perform(post("/api/transfers/external")
                        .header("Idempotency-Key", "wh-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sourceAccountId\": \"%s\", \"beneficiaryId\": \"%s\", \"amountMinor\": %d, \"narration\": \"test\"}"
                                .formatted(source, beneficiary, amountMinor)))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        dispatcher.dispatchDue();
        return new Started(UUID.fromString(JsonPath.read(json, "$.id")), JsonPath.read(json, "$.reference"));
    }

    private static long newEventId() {
        return ThreadLocalRandom.current().nextLong(1_000_000, 900_000_000);
    }

    private static String paystackBody(String event, String reference, long amountMinor, long dataId) {
        return ("{\"event\":\"%s\",\"data\":{\"id\":%d,\"reference\":\"%s\",\"amount\":%d,\"currency\":\"NGN\","
                + "\"transfer_code\":\"TRF_%d\",\"reason\":\"test reason\"}}").formatted(event, dataId, reference, amountMinor, dataId);
    }

    private static String monnifyBody(String eventType, String reference, String nairaAmount, String transactionReference) {
        return ("{\"eventType\":\"%s\",\"eventData\":{\"reference\":\"%s\",\"transactionReference\":\"%s\","
                + "\"amount\":%s,\"currency\":\"NGN\",\"status\":\"x\"}}").formatted(eventType, reference, transactionReference, nairaAmount);
    }

    private static MockHttpServletRequestBuilder signedWith(String path, String header, String signature, byte[] body) {
        return post(path).header(header, signature).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private static MockHttpServletRequestBuilder paystack(String body) {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        return signedWith("/api/webhooks/paystack", "x-paystack-signature", Hmac.hmacSha512Hex(PAYSTACK_SECRET, bytes), bytes);
    }

    private static MockHttpServletRequestBuilder monnify(String body) {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        return signedWith("/api/webhooks/monnify", "monnify-signature", Hmac.hmacSha512Hex(MONNIFY_SECRET, bytes), bytes);
    }

    private String result(MvcResult response) throws Exception {
        return JsonPath.read(response.getResponse().getContentAsString(), "$.result");
    }

    private String webhookStatus(String provider, String eventId) {
        List<String> found = jdbc.queryForList(
                "select status from webhook_event where provider = ? and event_id = ?", String.class, provider, eventId);
        return found.isEmpty() ? null : found.get(0);
    }

    private long webhookRows(String reference) {
        Long count = jdbc.queryForObject("select count(*) from webhook_event where reference = ?", Long.class, reference);
        return count == null ? 0 : count;
    }

    private long entryCount(String type, String reference) {
        Long count = jdbc.queryForObject(
                "select count(*) from journal_entry where entry_type = ? and reference = ?", Long.class, type, reference);
        return count == null ? 0 : count;
    }

    private double count(String provider, String result) {
        Counter counter = meters.find("paybridge.webhooks").tag("provider", provider).tag("result", result).counter();
        return counter == null ? 0 : counter.count();
    }

    // ------------------------------------------------------------------ Paystack: the three outcomes

    @Test
    void aSuccessWebhookFinishesATransferThatWasStillProcessing() throws Exception {
        UUID source = newFundedAccount(300_000);
        Started transfer = startAndDispatch(source, "6666", AMOUNT);
        assertThat(statusOf(transfer.id())).as("the bank is still working on it").isEqualTo(TransferStatus.PROCESSING);
        long paystackBefore = systemBalance(SystemAccount.PAYSTACK_SETTLEMENT);
        long feesBefore = systemBalance(SystemAccount.FEES);
        long suspenseBefore = systemBalance(SystemAccount.SUSPENSE);

        MvcResult response = mockMvc.perform(paystack(paystackBody("transfer.success", transfer.reference(), AMOUNT, newEventId())))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(result(response)).isEqualTo("APPLIED");
        assertThat(statusOf(transfer.id())).isEqualTo(TransferStatus.SUCCESS);
        assertThat(balance(source)).isEqualTo(300_000 - TOTAL);
        assertThat(systemBalance(SystemAccount.PAYSTACK_SETTLEMENT)).isEqualTo(paystackBefore + AMOUNT);
        assertThat(systemBalance(SystemAccount.FEES)).isEqualTo(feesBefore + FEE);
        assertThat(systemBalance(SystemAccount.SUSPENSE)).isEqualTo(suspenseBefore - TOTAL);
        assertThat(entryCount("EXTERNAL_SETTLEMENT", transfer.reference())).isEqualTo(1);
    }

    @Test
    void aFailureWebhookRefundsTheAmountAndTheFee() throws Exception {
        UUID source = newFundedAccount(300_000);
        Started transfer = startAndDispatch(source, "6666", AMOUNT);
        assertThat(balance(source)).isEqualTo(300_000 - TOTAL);

        MvcResult response = mockMvc.perform(paystack(paystackBody("transfer.failed", transfer.reference(), AMOUNT, newEventId())))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(result(response)).isEqualTo("APPLIED");
        assertThat(statusOf(transfer.id())).isEqualTo(TransferStatus.FAILED);
        assertThat(balance(source)).as("everything is back").isEqualTo(300_000);
        assertThat(transferApi.getTransfer(transfer.id()).failureReason()).isEqualTo("test reason");
    }

    @Test
    void aReversalWebhookAfterSuccessRefundsEverything() throws Exception {
        UUID source = newFundedAccount(300_000);
        Started transfer = startAndDispatch(source, "7777", AMOUNT);
        assertThat(statusOf(transfer.id())).as("the bank paid at once").isEqualTo(TransferStatus.SUCCESS);
        assertThat(balance(source)).isEqualTo(300_000 - TOTAL);

        MvcResult response = mockMvc.perform(paystack(paystackBody("transfer.reversed", transfer.reference(), AMOUNT, newEventId())))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(result(response)).isEqualTo("APPLIED");
        assertThat(statusOf(transfer.id())).isEqualTo(TransferStatus.REVERSED);
        assertThat(balance(source)).as("the customer has it all back, fee included").isEqualTo(300_000);
        assertThat(entryCount("REVERSAL", "unsettle:" + transfer.reference())).isEqualTo(1);
    }

    // ------------------------------------------------------------------ repeats

    @Test
    void theSameDeliveryTwiceChangesNothingTheSecondTime() throws Exception {
        UUID source = newFundedAccount(300_000);
        Started transfer = startAndDispatch(source, "6666", AMOUNT);
        String body = paystackBody("transfer.success", transfer.reference(), AMOUNT, newEventId());

        MvcResult first = mockMvc.perform(paystack(body)).andExpect(status().isOk()).andReturn();
        long paystackAfterFirst = systemBalance(SystemAccount.PAYSTACK_SETTLEMENT);
        MvcResult second = mockMvc.perform(paystack(body)).andExpect(status().isOk()).andReturn();

        assertThat(result(first)).isEqualTo("APPLIED");
        assertThat(result(second)).isEqualTo("DUPLICATE");
        assertThat(systemBalance(SystemAccount.PAYSTACK_SETTLEMENT)).isEqualTo(paystackAfterFirst);
        assertThat(entryCount("EXTERNAL_SETTLEMENT", transfer.reference())).isEqualTo(1);
        assertThat(webhookRows(transfer.reference())).isEqualTo(1);
    }

    @Test
    void aDifferentEventForTheSameTransferIsNotAnEchoOfTheFirst() throws Exception {
        UUID source = newFundedAccount(300_000);
        Started transfer = startAndDispatch(source, "7777", AMOUNT);
        long dataId = newEventId();

        mockMvc.perform(paystack(paystackBody("transfer.reversed", transfer.reference(), AMOUNT, dataId))).andExpect(status().isOk());
        MvcResult later = mockMvc.perform(paystack(paystackBody("transfer.reversed", transfer.reference(), AMOUNT, dataId + 1)))
                .andExpect(status().isOk()).andReturn();

        // a new delivery id, but the transfer is already reversed: applying it again changes nothing
        assertThat(result(later)).isEqualTo("APPLIED");
        assertThat(entryCount("REVERSAL", "unsettle:" + transfer.reference())).isEqualTo(1);
        assertThat(balance(source)).isEqualTo(300_000);
    }

    @Test
    void manyIdenticalDeliveriesAtTheSameInstantApplyExactlyOnce() throws Exception {
        UUID source = newFundedAccount(300_000);
        Started transfer = startAndDispatch(source, "6666", AMOUNT);
        String body = paystackBody("transfer.success", transfer.reference(), AMOUNT, newEventId());
        int deliveries = 20;

        ExecutorService pool = Executors.newFixedThreadPool(deliveries);
        CountDownLatch startingGun = new CountDownLatch(1);
        List<Future<MvcResult>> futures = new ArrayList<>();
        for (int i = 0; i < deliveries; i++) {
            Callable<MvcResult> delivery = () -> {
                startingGun.await();
                return mockMvc.perform(paystack(body)).andReturn();
            };
            futures.add(pool.submit(delivery));
        }
        startingGun.countDown();

        int applied = 0;
        for (Future<MvcResult> future : futures) {
            MvcResult response = future.get(30, TimeUnit.SECONDS);
            int httpStatus = response.getResponse().getStatus();
            assertThat(httpStatus).as("each delivery is acknowledged or told to retry").isIn(200, 409);
            if (httpStatus == 200 && "APPLIED".equals(result(response))) {
                applied++;
            }
        }
        pool.shutdown();

        assertThat(applied).as("exactly one delivery did the work").isEqualTo(1);
        assertThat(entryCount("EXTERNAL_SETTLEMENT", transfer.reference())).isEqualTo(1);
        assertThat(webhookRows(transfer.reference())).isEqualTo(1);
        assertThat(statusOf(transfer.id())).isEqualTo(TransferStatus.SUCCESS);
    }

    // ------------------------------------------------------------------ authenticity

    @Test
    void aWrongSignatureIsRefusedAndNothingChanges() throws Exception {
        UUID source = newFundedAccount(300_000);
        Started transfer = startAndDispatch(source, "6666", AMOUNT);
        byte[] body = paystackBody("transfer.success", transfer.reference(), AMOUNT, newEventId()).getBytes(StandardCharsets.UTF_8);
        double before = count("paystack", "bad_signature");

        mockMvc.perform(signedWith("/api/webhooks/paystack", "x-paystack-signature", Hmac.hmacSha512Hex("a-forgers-guess", body), body))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

        assertThat(statusOf(transfer.id())).isEqualTo(TransferStatus.PROCESSING);
        assertThat(webhookRows(transfer.reference())).as("a refused webhook leaves no record").isZero();
        assertThat(count("paystack", "bad_signature")).isEqualTo(before + 1);
    }

    @Test
    void aMissingSignatureIsRefused() throws Exception {
        UUID source = newFundedAccount(300_000);
        Started transfer = startAndDispatch(source, "6666", AMOUNT);

        mockMvc.perform(post("/api/webhooks/paystack").contentType(MediaType.APPLICATION_JSON)
                        .content(paystackBody("transfer.success", transfer.reference(), AMOUNT, newEventId())))
                .andExpect(status().isUnauthorized());

        assertThat(statusOf(transfer.id())).isEqualTo(TransferStatus.PROCESSING);
    }

    @Test
    void aSignatureForADifferentBodyIsRefused() throws Exception {
        UUID source = newFundedAccount(300_000);
        Started transfer = startAndDispatch(source, "6666", AMOUNT);
        long dataId = newEventId();
        byte[] harmless = paystackBody("transfer.failed", transfer.reference(), AMOUNT, dataId).getBytes(StandardCharsets.UTF_8);
        byte[] forged = paystackBody("transfer.success", transfer.reference(), AMOUNT, dataId).getBytes(StandardCharsets.UTF_8);

        mockMvc.perform(signedWith("/api/webhooks/paystack", "x-paystack-signature", Hmac.hmacSha512Hex(PAYSTACK_SECRET, harmless), forged))
                .andExpect(status().isUnauthorized());

        assertThat(statusOf(transfer.id())).isEqualTo(TransferStatus.PROCESSING);
    }

    @Test
    void aSignatureFromTheOtherProvidersSecretIsRefused() throws Exception {
        byte[] body = paystackBody("transfer.success", "pb-whatever", AMOUNT, newEventId()).getBytes(StandardCharsets.UTF_8);

        mockMvc.perform(signedWith("/api/webhooks/paystack", "x-paystack-signature", Hmac.hmacSha512Hex(MONNIFY_SECRET, body), body))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void theSignatureIsCheckedOnTheExactBytesSoPrettyPrintedJsonStillWorksWhenSignedAsSent() throws Exception {
        UUID source = newFundedAccount(300_000);
        Started transfer = startAndDispatch(source, "6666", AMOUNT);
        String pretty = paystackBody("transfer.success", transfer.reference(), AMOUNT, newEventId()).replace(",", ",\n    ");
        byte[] bytes = pretty.getBytes(StandardCharsets.UTF_8);

        mockMvc.perform(signedWith("/api/webhooks/paystack", "x-paystack-signature", Hmac.hmacSha512Hex(PAYSTACK_SECRET, bytes), bytes))
                .andExpect(status().isOk());

        assertThat(statusOf(transfer.id())).isEqualTo(TransferStatus.SUCCESS);
    }

    @Test
    void anUnknownProviderIsNotFound() throws Exception {
        mockMvc.perform(post("/api/webhooks/flutterwave").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    void theProviderNameIsNotCaseSensitive() throws Exception {
        byte[] body = paystackBody("charge.success", "x", 1, newEventId()).getBytes(StandardCharsets.UTF_8);

        mockMvc.perform(signedWith("/api/webhooks/PayStack", "x-paystack-signature", Hmac.hmacSha512Hex(PAYSTACK_SECRET, body), body))
                .andExpect(status().isOk());
    }

    // ------------------------------------------------------------------ things that are not what they seem

    @Test
    void aReferenceWeDoNotKnowIsAcknowledgedAndRecordedButChangesNothing() throws Exception {
        String reference = "pb-" + UUID.randomUUID();
        long dataId = newEventId();

        MvcResult response = mockMvc.perform(paystack(paystackBody("transfer.success", reference, AMOUNT, dataId)))
                .andExpect(status().isOk()).andReturn();

        assertThat(result(response)).isEqualTo("UNMATCHED");
        assertThat(webhookStatus("paystack", "transfer.success:" + dataId)).isEqualTo("UNMATCHED");
    }

    @Test
    void aReferenceBelongingToAnInternalTransferIsNotActedOn() throws Exception {
        UUID from = newFundedAccount(300_000);
        String toNumber = accountApi.getAccount(newAccount()).accountNumber();
        String reference = transferService.transferInternal(new InternalTransferCommand(from, toNumber, 10_000, "internal"),
                "wh-" + UUID.randomUUID()).transfer().reference();

        MvcResult response = mockMvc.perform(paystack(paystackBody("transfer.reversed", reference, 10_000, newEventId())))
                .andExpect(status().isOk()).andReturn();

        assertThat(result(response)).isEqualTo("UNMATCHED");
        assertThat(balance(from)).isEqualTo(290_000);
    }

    @Test
    void anAmountThatDisagreesWithOurRecordIsNotAppliedAndIsFlaggedForAPerson() throws Exception {
        UUID source = newFundedAccount(300_000);
        Started transfer = startAndDispatch(source, "6666", AMOUNT);
        long dataId = newEventId();

        MvcResult response = mockMvc.perform(paystack(paystackBody("transfer.success", transfer.reference(), AMOUNT + 1, dataId)))
                .andExpect(status().isOk()).andReturn();

        assertThat(result(response)).isEqualTo("AMOUNT_MISMATCH");
        assertThat(statusOf(transfer.id())).isEqualTo(TransferStatus.PROCESSING);
        assertThat(webhookStatus("paystack", "transfer.success:" + dataId)).isEqualTo("AMOUNT_MISMATCH");
        assertThat(jdbc.queryForObject("select detail from webhook_event where provider = 'paystack' and event_id = ?",
                String.class, "transfer.success:" + dataId)).contains(String.valueOf(AMOUNT + 1)).contains(String.valueOf(AMOUNT));
    }

    @Test
    void aCurrencyThatDisagreesWithOurRecordIsNotApplied() throws Exception {
        UUID source = newFundedAccount(300_000);
        Started transfer = startAndDispatch(source, "6666", AMOUNT);
        String usd = paystackBody("transfer.success", transfer.reference(), AMOUNT, newEventId()).replace("\"NGN\"", "\"USD\"");

        MvcResult response = mockMvc.perform(paystack(usd)).andExpect(status().isOk()).andReturn();

        assertThat(result(response)).isEqualTo("AMOUNT_MISMATCH");
        assertThat(statusOf(transfer.id())).isEqualTo(TransferStatus.PROCESSING);
    }

    @Test
    void aLateSuccessForATransferAlreadyRefundedGoesToManualReviewAndMovesNoMoney() throws Exception {
        UUID source = newFundedAccount(300_000);
        Started transfer = startAndDispatch(source, "1111", AMOUNT);   // the bank refused: already refunded
        assertThat(statusOf(transfer.id())).isEqualTo(TransferStatus.FAILED);
        assertThat(balance(source)).isEqualTo(300_000);
        long dataId = newEventId();

        MvcResult response = mockMvc.perform(paystack(paystackBody("transfer.success", transfer.reference(), AMOUNT, dataId)))
                .andExpect(status().isOk()).andReturn();

        assertThat(result(response)).isEqualTo("MANUAL_REVIEW");
        assertThat(statusOf(transfer.id())).isEqualTo(TransferStatus.FAILED);
        assertThat(balance(source)).isEqualTo(300_000);
        assertThat(webhookStatus("paystack", "transfer.success:" + dataId)).isEqualTo("MANUAL_REVIEW");
    }

    @Test
    void eventsThatAreNotTransferOutcomesAreRecordedAndIgnored() throws Exception {
        long dataId = newEventId();

        MvcResult response = mockMvc.perform(paystack(paystackBody("charge.success", "some-payment", 5_000, dataId)))
                .andExpect(status().isOk()).andReturn();

        assertThat(result(response)).isEqualTo("IGNORED");
        assertThat(webhookStatus("paystack", "charge.success:" + dataId)).isEqualTo("IGNORED");
    }

    @Test
    void aTransferEventWithNoReferenceIsUnmatched() throws Exception {
        long dataId = newEventId();
        String body = "{\"event\":\"transfer.success\",\"data\":{\"id\":" + dataId + ",\"amount\":100}}";

        MvcResult response = mockMvc.perform(paystack(body)).andExpect(status().isOk()).andReturn();

        assertThat(result(response)).isEqualTo("UNMATCHED");
    }

    // ------------------------------------------------------------------ bad requests

    @Test
    void signedGarbageIsABadRequestAndLeavesNoRecord() throws Exception {
        byte[] body = "this is not json".getBytes(StandardCharsets.UTF_8);

        mockMvc.perform(signedWith("/api/webhooks/paystack", "x-paystack-signature", Hmac.hmacSha512Hex(PAYSTACK_SECRET, body), body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void signedJsonThatIsNotAnObjectIsABadRequest() throws Exception {
        byte[] body = "[1,2,3]".getBytes(StandardCharsets.UTF_8);

        mockMvc.perform(signedWith("/api/webhooks/paystack", "x-paystack-signature", Hmac.hmacSha512Hex(PAYSTACK_SECRET, body), body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void anOversizedBodyIsRefusedBeforeAnythingElse() throws Exception {
        byte[] huge = "a".repeat(70_000).getBytes(StandardCharsets.UTF_8);

        mockMvc.perform(post("/api/webhooks/paystack").contentType(MediaType.APPLICATION_JSON).content(huge))
                .andExpect(status().is(413))
                .andExpect(jsonPath("$.code").value("PAYLOAD_TOO_LARGE"));
    }

    @Test
    void aBodyAtTheLimitIsStillRead() throws Exception {
        byte[] atLimit = "a".repeat(65_536).getBytes(StandardCharsets.UTF_8);

        mockMvc.perform(post("/api/webhooks/paystack").contentType(MediaType.APPLICATION_JSON).content(atLimit))
                .andExpect(status().isUnauthorized());   // read in full, then refused for the missing signature
    }

    @Test
    void getIsNotAllowedOnTheWebhookAddress() throws Exception {
        mockMvc.perform(get("/api/webhooks/paystack")).andExpect(status().isMethodNotAllowed());
    }

    // ------------------------------------------------------------------ Monnify

    @Test
    void monnifySuccessFinishesAProcessingTransferAndReadsNairaAsKobo() throws Exception {
        UUID source = newFundedAccount(300_000);
        Started transfer = startAndDispatch(source, "6666", AMOUNT);

        MvcResult response = mockMvc.perform(monnify(monnifyBody("SUCCESSFUL_DISBURSEMENT", transfer.reference(), "2000.00", "MFDS|" + newEventId())))
                .andExpect(status().isOk()).andReturn();

        assertThat(result(response)).isEqualTo("APPLIED");
        assertThat(statusOf(transfer.id())).isEqualTo(TransferStatus.SUCCESS);
    }

    @Test
    void monnifyFailureRefundsEverything() throws Exception {
        UUID source = newFundedAccount(300_000);
        Started transfer = startAndDispatch(source, "6666", AMOUNT);

        mockMvc.perform(monnify(monnifyBody("FAILED_DISBURSEMENT", transfer.reference(), "2000", "MFDS|" + newEventId())))
                .andExpect(status().isOk());

        assertThat(statusOf(transfer.id())).isEqualTo(TransferStatus.FAILED);
        assertThat(balance(source)).isEqualTo(300_000);
    }

    @Test
    void monnifyReversalAfterSuccessRefundsEverything() throws Exception {
        UUID source = newFundedAccount(300_000);
        Started transfer = startAndDispatch(source, "7777", AMOUNT);

        mockMvc.perform(monnify(monnifyBody("REVERSED_DISBURSEMENT", transfer.reference(), "2000.00", "MFDS|" + newEventId())))
                .andExpect(status().isOk());

        assertThat(statusOf(transfer.id())).isEqualTo(TransferStatus.REVERSED);
        assertThat(balance(source)).isEqualTo(300_000);
    }

    @Test
    void monnifyAmountsThatDisagreeAreNotApplied() throws Exception {
        UUID source = newFundedAccount(300_000);
        Started transfer = startAndDispatch(source, "6666", AMOUNT);

        MvcResult response = mockMvc.perform(monnify(monnifyBody("SUCCESSFUL_DISBURSEMENT", transfer.reference(), "2000.01", "MFDS|" + newEventId())))
                .andExpect(status().isOk()).andReturn();

        assertThat(result(response)).isEqualTo("AMOUNT_MISMATCH");
        assertThat(statusOf(transfer.id())).isEqualTo(TransferStatus.PROCESSING);
    }

    @Test
    void monnifyRepeatsAreRecognised() throws Exception {
        UUID source = newFundedAccount(300_000);
        Started transfer = startAndDispatch(source, "6666", AMOUNT);
        String body = monnifyBody("SUCCESSFUL_DISBURSEMENT", transfer.reference(), "2000.00", "MFDS|" + newEventId());

        mockMvc.perform(monnify(body)).andExpect(status().isOk());
        MvcResult again = mockMvc.perform(monnify(body)).andExpect(status().isOk()).andReturn();

        assertThat(result(again)).isEqualTo("DUPLICATE");
        assertThat(entryCount("EXTERNAL_SETTLEMENT", transfer.reference())).isEqualTo(1);
    }

    @Test
    void monnifyWithTheWrongSignatureIsRefused() throws Exception {
        byte[] body = monnifyBody("SUCCESSFUL_DISBURSEMENT", "pb-x", "1.00", "MFDS|1").getBytes(StandardCharsets.UTF_8);

        mockMvc.perform(signedWith("/api/webhooks/monnify", "monnify-signature", Hmac.hmacSha512Hex("nope", body), body))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void aPaystackSignatureDoesNotWorkOnTheMonnifyAddress() throws Exception {
        byte[] body = monnifyBody("SUCCESSFUL_DISBURSEMENT", "pb-x", "1.00", "MFDS|2").getBytes(StandardCharsets.UTF_8);

        mockMvc.perform(signedWith("/api/webhooks/monnify", "x-paystack-signature", Hmac.hmacSha512Hex(MONNIFY_SECRET, body), body))
                .andExpect(status().isUnauthorized());
    }

    // ------------------------------------------------------------------ metrics

    @Test
    void outcomesAreCounted() throws Exception {
        double appliedBefore = count("paystack", "applied");
        double duplicateBefore = count("paystack", "duplicate");
        UUID source = newFundedAccount(300_000);
        Started transfer = startAndDispatch(source, "6666", AMOUNT);
        String body = paystackBody("transfer.success", transfer.reference(), AMOUNT, newEventId());

        mockMvc.perform(paystack(body));
        mockMvc.perform(paystack(body));

        assertThat(count("paystack", "applied")).isEqualTo(appliedBefore + 1);
        assertThat(count("paystack", "duplicate")).isEqualTo(duplicateBefore + 1);
    }

    // ------------------------------------------------------------------ through the real security filters

    @Test
    void aProviderNeedsNoApiKeyOnTheWebhookAddress() throws Exception {
        MvcResult response = secured().perform(paystack(paystackBody("charge.success", "x", 1, newEventId())))
                .andExpect(status().isOk()).andReturn();

        assertThat(result(response)).isEqualTo("IGNORED");
    }

    @Test
    void theOpenWebhookAddressDoesNotOpenAnythingElse() throws Exception {
        var mvc = secured();

        mvc.perform(get("/api/webhooks/../admin/api-keys")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/customers/" + UUID.randomUUID())).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/webhooksx/paystack")).andExpect(status().isUnauthorized());
    }

    @Test
    void aBadSignatureThroughTheFiltersIsStillRefusedByTheWebhookCheck() throws Exception {
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);

        secured().perform(signedWith("/api/webhooks/paystack", "x-paystack-signature", "00", body))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value("The webhook signature is missing or invalid"));
    }

    @Test
    void oneAddressCannotFloodTheWebhookAddress() throws Exception {
        var mvc = secured(ENABLED, new RateLimiter(1_000, clock), new RateLimiter(1_000, clock), new RateLimiter(1_000, clock),
                new RateLimiter(2, clock));
        String flooder = "198.51.100.77";

        for (int i = 0; i < 2; i++) {
            mvc.perform(paystack(paystackBody("charge.success", "x", 1, newEventId())).with(request -> {
                request.setRemoteAddr(flooder);
                return request;
            })).andExpect(status().isOk());
        }

        mvc.perform(paystack(paystackBody("charge.success", "x", 1, newEventId())).with(request -> {
                    request.setRemoteAddr(flooder);
                    return request;
                }))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"));

        mvc.perform(paystack(paystackBody("charge.success", "x", 1, newEventId())).with(request -> {
            request.setRemoteAddr("198.51.100.78");
            return request;
        })).andExpect(status().isOk());
    }
}
