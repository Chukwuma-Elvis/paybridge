package com.academy.paybridge.transfer;

import com.academy.paybridge.AbstractIntegrationTest;
import com.academy.paybridge.account.api.AccountApi;
import com.academy.paybridge.account.api.SystemAccount;
import com.academy.paybridge.compliance.api.ComplianceApi;
import com.academy.paybridge.ledger.api.EntryType;
import com.academy.paybridge.ledger.api.LedgerApi;
import com.academy.paybridge.shared.exception.ErrorCode;
import com.academy.paybridge.shared.exception.PayBridgeException;
import com.academy.paybridge.transfer.api.TransferApi;
import com.academy.paybridge.transfer.api.TransferCompleted;
import com.academy.paybridge.transfer.api.TransferFailed;
import com.academy.paybridge.transfer.api.TransferReversed;
import com.academy.paybridge.transfer.api.TransferStatus;
import com.academy.paybridge.transfer.api.TransferSummary;
import com.academy.paybridge.transfer.client.FakeTransferGateway;
import com.academy.paybridge.transfer.client.GatewayOutcome;
import com.academy.paybridge.transfer.service.ExternalTransferCommand;
import com.academy.paybridge.transfer.service.ExternalTransferService;
import com.academy.paybridge.transfer.service.TransferDispatcher;
import com.academy.paybridge.transfer.service.TransferReconciler;
import com.academy.paybridge.transfer.service.TransferResult;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.ResultActions;

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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The external-transfer flow, driven step by step. The dispatcher and reconciler timers are off in the test profile,
 * so each test calls them itself and can check exactly what happened between steps.
 * <p>
 * The fake bank's behaviour is chosen by the LAST FOUR DIGITS of the beneficiary's account number
 * (see {@link FakeTransferGateway}): 3333 succeeds at once, 1111 is refused, 2222 and 5555 time out, and so on.
 * <p>
 * Fee bands used here: up to NGN 5,000 costs NGN 10 (1,000 kobo).
 */
@RecordApplicationEvents
class ExternalTransferTest extends AbstractIntegrationTest {

    private static final long AMOUNT = 200_000;   // NGN 2,000
    private static final long FEE = 1_000;        // NGN 10
    private static final long TOTAL = AMOUNT + FEE;

    @Autowired AccountApi accountApi;
    @Autowired LedgerApi ledgerApi;
    @Autowired ComplianceApi complianceApi;
    @Autowired TransferApi transferApi;
    @Autowired TransferDispatcher dispatcher;
    @Autowired TransferReconciler reconciler;
    @Autowired ExternalTransferService externalTransferService;
    @Autowired FakeTransferGateway fakeBank;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired ApplicationEvents applicationEvents;

    private record Started(UUID id, String reference) {
    }

    // ---------------------------------------------------------------- helpers

    @AfterEach
    void theBankStillAddsUpToZero() {
        Long total = jdbcTemplate.queryForObject("select coalesce(sum(balance_minor), 0) from account", Long.class);
        assertThat(total).as("money must never be created or destroyed").isZero();
    }

    private long balance(UUID accountId) {
        return accountApi.getAccount(accountId).balanceMinor();
    }

    private long systemBalance(SystemAccount account) {
        return balance(accountApi.systemAccountId(account));
    }

    private UUID customerOf(UUID accountId) {
        return accountApi.getAccount(accountId).ownerCustomerId();
    }

    private long usedToday(UUID customerId) {
        return complianceApi.limitStatus(customerId).usedTodayMinor();
    }

    private TransferSummary summary(UUID transferId) {
        return transferApi.getTransfer(transferId);
    }

    private UUID beneficiary(UUID customerId, String endingDigits) throws Exception {
        String accountNumber = String.format("%06d", ThreadLocalRandom.current().nextInt(1_000_000)) + endingDigits;
        String json = mockMvc.perform(post("/api/beneficiaries")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"customerId": "%s", "bankCode": "058", "accountNumber": "%s"}
                                """.formatted(customerId, accountNumber)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(json, "$.id"));
    }

    private ResultActions send(String idempotencyKey, UUID source, UUID beneficiary, long amountMinor) throws Exception {
        return mockMvc.perform(post("/api/transfers/external")
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"sourceAccountId": "%s", "beneficiaryId": "%s", "amountMinor": %d, "narration": "test"}
                        """.formatted(source, beneficiary, amountMinor)));
    }

    /** Asks for an external transfer to an account at the fake bank with the given ending digits. */
    private Started start(UUID source, String endingDigits, long amountMinor) throws Exception {
        UUID beneficiary = beneficiary(customerOf(source), endingDigits);
        String json = send("ext-" + UUID.randomUUID(), source, beneficiary, amountMinor)
                .andExpect(status().isAccepted())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andReturn().getResponse().getContentAsString();
        return new Started(UUID.fromString(JsonPath.read(json, "$.id")), JsonPath.read(json, "$.reference"));
    }

    private long entryCount(EntryType type, String reference) {
        Long count = jdbcTemplate.queryForObject(
                "select count(*) from journal_entry where entry_type = ? and reference = ?",
                Long.class, type.name(), reference);
        return count == null ? 0 : count;
    }

    private String outboxStatus(UUID transferId) {
        return jdbcTemplate.queryForObject(
                "select status from transfer_outbox where transfer_id = ?", String.class, transferId);
    }

    // ---------------------------------------------------------------- 1. accepting the request

    @Test
    void acceptingATransferHoldsTheMoneyAndFeeButDoesNotCallTheBank() throws Exception {
        UUID source = newFundedAccount(300_000);
        long suspenseBefore = systemBalance(SystemAccount.SUSPENSE);

        Started transfer = start(source, "3333", AMOUNT);

        TransferSummary summary = summary(transfer.id());
        assertThat(summary.status()).isEqualTo(TransferStatus.PENDING);
        assertThat(summary.type().name()).isEqualTo("EXTERNAL");
        assertThat(summary.amountMinor()).isEqualTo(AMOUNT);
        assertThat(summary.feeMinor()).isEqualTo(FEE);
        assertThat(summary.beneficiaryId()).isNotNull();
        assertThat(summary.destinationAccountId()).isNull();

        assertThat(balance(source)).as("amount + fee are already on hold").isEqualTo(300_000 - TOTAL);
        assertThat(systemBalance(SystemAccount.SUSPENSE)).isEqualTo(suspenseBefore + TOTAL);
        assertThat(ledgerApi.find(EntryType.EXTERNAL_HOLD, transfer.reference())).isPresent();
        assertThat(outboxStatus(transfer.id())).isEqualTo("PENDING");
        assertThat(fakeBank.initiateCalls(transfer.reference())).as("the bank has not been contacted yet").isZero();
        assertThat(usedToday(customerOf(source))).isEqualTo(AMOUNT);
    }

    @Test
    void theIdempotencyKeyMakesARetryHarmless() throws Exception {
        UUID source = newFundedAccount(300_000);
        UUID beneficiary = beneficiary(customerOf(source), "3333");
        String key = "ext-" + UUID.randomUUID();

        String first = send(key, source, beneficiary, AMOUNT).andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        String second = send(key, source, beneficiary, AMOUNT).andExpect(status().isOk())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andReturn().getResponse().getContentAsString();

        assertThat((String) JsonPath.read(second, "$.id")).isEqualTo(JsonPath.read(first, "$.id"));
        assertThat(balance(source)).as("held once, not twice").isEqualTo(300_000 - TOTAL);
        send(key, source, beneficiary, AMOUNT + 1).andExpect(status().is(422))
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
    }

    @Test
    void theBalanceMustCoverTheFeeAsWellAsTheAmount() throws Exception {
        UUID source = newFundedAccount(200_500);   // enough for 200,000 but not for 200,000 + 1,000 fee
        UUID beneficiary = beneficiary(customerOf(source), "3333");

        send("ext-" + UUID.randomUUID(), source, beneficiary, AMOUNT)
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_FUNDS"));

        assertThat(balance(source)).isEqualTo(200_500);
        assertThat(usedToday(customerOf(source))).as("a refused request uses no allowance").isZero();
        Long transfers = jdbcTemplate.queryForObject(
                "select count(*) from transfer where source_account_id = ?", Long.class, source);
        assertThat(transfers).isZero();
    }

    @Test
    void tinyAmountsAreRefused() throws Exception {
        UUID source = newFundedAccount(300_000);
        UUID beneficiary = beneficiary(customerOf(source), "3333");

        send("ext-" + UUID.randomUUID(), source, beneficiary, 5_000)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        assertThat(balance(source)).isEqualTo(300_000);
    }

    @Test
    void youCannotPayThroughSomeoneElsesSavedPayee() throws Exception {
        UUID mine = newFundedAccount(300_000);
        UUID theirsCustomer = createCustomer();
        UUID theirBeneficiary = beneficiary(theirsCustomer, "3333");

        send("ext-" + UUID.randomUUID(), mine, theirBeneficiary, AMOUNT)
                .andExpect(status().isNotFound());
        assertThat(balance(mine)).isEqualTo(300_000);
    }

    @Test
    void anUnknownPayeeIsNotFound() throws Exception {
        UUID source = newFundedAccount(300_000);

        send("ext-" + UUID.randomUUID(), source, UUID.randomUUID(), AMOUNT).andExpect(status().isNotFound());
    }

    @Test
    void theCustomersLimitsApplyToExternalTransfersToo() throws Exception {
        UUID source = newFundedAccount(9_000_000);
        UUID beneficiary = beneficiary(customerOf(source), "3333");

        send("ext-" + UUID.randomUUID(), source, beneficiary, 5_000_001) // over the NGN 50,000 UNVERIFIED limit
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.code").value("LIMIT_EXCEEDED"));
        assertThat(balance(source)).isEqualTo(9_000_000);
    }

    @Test
    void aFrozenAccountCannotStartATransfer() throws Exception {
        UUID source = newFundedAccount(300_000);
        UUID beneficiary = beneficiary(customerOf(source), "3333");
        jdbcTemplate.update("update account set status = 'FROZEN' where id = ?", source);

        send("ext-" + UUID.randomUUID(), source, beneficiary, AMOUNT)
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.code").value("ACCOUNT_NOT_ACTIVE"));
    }

    @Test
    void requiredFieldsAreChecked() throws Exception {
        mockMvc.perform(post("/api/transfers/external")
                        .header("Idempotency-Key", "ext-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amountMinor\": 100000}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.sourceAccountId").exists())
                .andExpect(jsonPath("$.errors.beneficiaryId").exists());
    }

    // ---------------------------------------------------------------- 2. the provider says yes

    @Test
    void whenTheBankSaysYesTheMoneyIsPaidOutAndTheFeeIsEarned() throws Exception {
        UUID source = newFundedAccount(300_000);
        Started transfer = start(source, "3333", AMOUNT);
        long suspenseBefore = systemBalance(SystemAccount.SUSPENSE);
        long paystackBefore = systemBalance(SystemAccount.PAYSTACK_SETTLEMENT);
        long feesBefore = systemBalance(SystemAccount.FEES);

        dispatcher.dispatch(transfer.id());

        assertThat(summary(transfer.id()).status()).isEqualTo(TransferStatus.SUCCESS);
        assertThat(summary(transfer.id()).completedAt()).isNotNull();
        assertThat(balance(source)).isEqualTo(300_000 - TOTAL);
        assertThat(systemBalance(SystemAccount.SUSPENSE)).as("the in-transit shelf is emptied")
                .isEqualTo(suspenseBefore - TOTAL);
        assertThat(systemBalance(SystemAccount.PAYSTACK_SETTLEMENT)).isEqualTo(paystackBefore + AMOUNT);
        assertThat(systemBalance(SystemAccount.FEES)).isEqualTo(feesBefore + FEE);
        assertThat(fakeBank.hasExecuted(transfer.reference())).isTrue();
        assertThat(outboxStatus(transfer.id())).isEqualTo("DONE");

        assertThat(applicationEvents.stream(TransferCompleted.class)
                .filter(event -> event.transferId().equals(transfer.id())))
                .singleElement()
                .satisfies(event -> {
                    assertThat(event.amountMinor()).isEqualTo(AMOUNT);
                    assertThat(event.feeMinor()).isEqualTo(FEE);
                });
    }

    @Test
    void anAcceptedTransferStaysInProgressUntilTheReconcilerHearsTheOutcome() throws Exception {
        UUID source = newFundedAccount(300_000);
        Started transfer = start(source, "1234", AMOUNT);       // ordinary account: pending, then success

        dispatcher.dispatch(transfer.id());

        assertThat(summary(transfer.id()).status()).isEqualTo(TransferStatus.PROCESSING);
        assertThat(balance(source)).as("still on hold").isEqualTo(300_000 - TOTAL);
        assertThat(ledgerApi.find(EntryType.EXTERNAL_SETTLEMENT, transfer.reference())).isEmpty();

        reconciler.reconcileTransfer(transfer.reference());

        assertThat(summary(transfer.id()).status()).isEqualTo(TransferStatus.SUCCESS);
        assertThat(ledgerApi.find(EntryType.EXTERNAL_SETTLEMENT, transfer.reference())).isPresent();
    }

    @Test
    void aTransferThatIsStillPendingAtTheBankIsLeftAlone() throws Exception {
        UUID source = newFundedAccount(300_000);
        Started transfer = start(source, "6666", AMOUNT);       // pending for ever
        dispatcher.dispatch(transfer.id());

        reconciler.reconcileTransfer(transfer.reference());
        reconciler.reconcileTransfer(transfer.reference());

        assertThat(summary(transfer.id()).status()).isEqualTo(TransferStatus.PROCESSING);
        assertThat(balance(source)).as("the money stays on hold while we wait").isEqualTo(300_000 - TOTAL);
    }

    // ---------------------------------------------------------------- 3. the provider says no

    @Test
    void whenTheBankRefusesTheCustomerGetsEverythingBack() throws Exception {
        UUID source = newFundedAccount(300_000);
        Started transfer = start(source, "1111", AMOUNT);
        long suspenseBefore = systemBalance(SystemAccount.SUSPENSE);

        dispatcher.dispatch(transfer.id());

        TransferSummary summary = summary(transfer.id());
        assertThat(summary.status()).isEqualTo(TransferStatus.FAILED);
        assertThat(summary.failureReason()).contains("Simulated refusal");
        assertThat(balance(source)).as("amount AND fee are returned").isEqualTo(300_000);
        assertThat(systemBalance(SystemAccount.SUSPENSE)).isEqualTo(suspenseBefore - TOTAL);
        assertThat(usedToday(customerOf(source))).as("the daily allowance is given back").isZero();
        assertThat(entryCount(EntryType.EXTERNAL_SETTLEMENT, transfer.reference())).isZero();
        assertThat(applicationEvents.stream(TransferFailed.class)
                .filter(event -> event.transferId().equals(transfer.id()))).hasSize(1);

        // history is kept: the hold and its reversal are both on the statement
        assertThat(ledgerApi.statement(source, 10)).extracting(line -> line.type())
                .contains(EntryType.EXTERNAL_HOLD, EntryType.REVERSAL);
    }

    @Test
    void aTransferTheBankLaterReportsAsFailedIsRefunded() throws Exception {
        UUID source = newFundedAccount(300_000);
        Started transfer = start(source, "4444", AMOUNT);       // accepted, later failed
        dispatcher.dispatch(transfer.id());
        assertThat(summary(transfer.id()).status()).isEqualTo(TransferStatus.PROCESSING);

        reconciler.reconcileTransfer(transfer.reference());

        assertThat(summary(transfer.id()).status()).isEqualTo(TransferStatus.FAILED);
        assertThat(balance(source)).isEqualTo(300_000);
    }

    // ---------------------------------------------------------------- 4. "we do not know": the dangerous cases

    @Test
    void aTimeoutBeforeTheBankGotTheRequestIsNeverTreatedAsAFailureUntilTheBankConfirmsIt() throws Exception {
        UUID source = newFundedAccount(300_000);
        Started transfer = start(source, "2222", AMOUNT);       // the bank never receives it

        dispatcher.dispatch(transfer.id());                      // try 1: timeout
        assertThat(summary(transfer.id()).status())
                .as("a timeout means UNKNOWN, not failed").isEqualTo(TransferStatus.PROCESSING);
        assertThat(balance(source)).as("the money stays on hold").isEqualTo(300_000 - TOTAL);
        assertThat(outboxStatus(transfer.id())).isEqualTo("PENDING");

        dispatcher.dispatch(transfer.id());                      // try 2: timeout
        dispatcher.dispatch(transfer.id());                      // try 3: timeout, out of retries
        assertThat(outboxStatus(transfer.id())).isEqualTo("DONE");
        assertThat(summary(transfer.id()).status()).isEqualTo(TransferStatus.PROCESSING);
        assertThat(fakeBank.initiateCalls(transfer.reference())).isEqualTo(3);

        reconciler.reconcileTransfer(transfer.reference());      // ask the bank: "never heard of it"

        assertThat(summary(transfer.id()).status()).isEqualTo(TransferStatus.FAILED);
        assertThat(summary(transfer.id()).failureReason()).contains("never reached");
        assertThat(balance(source)).as("now it is safe to refund").isEqualTo(300_000);
    }

    @Test
    void aTimeoutAfterTheBankGotTheRequestNeverPaysTwice() throws Exception {
        UUID source = newFundedAccount(300_000);
        Started transfer = start(source, "5555", AMOUNT);       // the bank DOES receive it, but we see a timeout
        long paystackBefore = systemBalance(SystemAccount.PAYSTACK_SETTLEMENT);

        dispatcher.dispatch(transfer.id());                      // timeout, yet the bank has it
        assertThat(summary(transfer.id()).status()).isEqualTo(TransferStatus.PROCESSING);
        assertThat(fakeBank.hasExecuted(transfer.reference())).isTrue();

        dispatcher.dispatch(transfer.id());                      // retry: the bank recognises our reference
        reconciler.reconcileTransfer(transfer.reference());

        assertThat(summary(transfer.id()).status()).isEqualTo(TransferStatus.SUCCESS);
        assertThat(fakeBank.initiateCalls(transfer.reference())).as("we asked twice...").isEqualTo(2);
        assertThat(entryCount(EntryType.EXTERNAL_SETTLEMENT, transfer.reference())).as("...but paid once").isEqualTo(1);
        assertThat(systemBalance(SystemAccount.PAYSTACK_SETTLEMENT)).isEqualTo(paystackBefore + AMOUNT);
        assertThat(balance(source)).isEqualTo(300_000 - TOTAL);
    }

    // ---------------------------------------------------------------- 5. reversals and repeated answers

    @Test
    void aTransferTheBankReversesAfterSucceedingIsFullyRefunded() throws Exception {
        UUID source = newFundedAccount(300_000);
        Started transfer = start(source, "7777", AMOUNT);
        long paystackBefore = systemBalance(SystemAccount.PAYSTACK_SETTLEMENT);
        long feesBefore = systemBalance(SystemAccount.FEES);
        dispatcher.dispatch(transfer.id());
        assertThat(summary(transfer.id()).status()).isEqualTo(TransferStatus.SUCCESS);

        // this is what a "transfer.reversed" webhook from the provider will do
        externalTransferService.applyProviderResult(transfer.reference(), GatewayOutcome.REVERSED, null,
                "returned by the recipient bank");

        assertThat(summary(transfer.id()).status()).isEqualTo(TransferStatus.REVERSED);
        assertThat(balance(source)).as("amount and fee both come back").isEqualTo(300_000);
        assertThat(systemBalance(SystemAccount.PAYSTACK_SETTLEMENT)).isEqualTo(paystackBefore);
        assertThat(systemBalance(SystemAccount.FEES)).isEqualTo(feesBefore);
        assertThat(usedToday(customerOf(source))).isZero();
        assertThat(applicationEvents.stream(TransferReversed.class)
                .filter(event -> event.transferId().equals(transfer.id()))).hasSize(1);
    }

    @Test
    void aFailureReportedAfterSuccessIsTreatedAsAReversal() throws Exception {
        UUID source = newFundedAccount(300_000);
        Started transfer = start(source, "3333", AMOUNT);
        dispatcher.dispatch(transfer.id());

        externalTransferService.applyProviderResult(transfer.reference(), GatewayOutcome.FAILED, null, "bank reversed it");

        assertThat(summary(transfer.id()).status()).isEqualTo(TransferStatus.REVERSED);
        assertThat(balance(source)).isEqualTo(300_000);
    }

    @Test
    void theSameAnswerRepeatedMovesMoneyOnlyOnce() throws Exception {
        UUID source = newFundedAccount(300_000);
        Started transfer = start(source, "3333", AMOUNT);
        long paystackBefore = systemBalance(SystemAccount.PAYSTACK_SETTLEMENT);
        dispatcher.dispatch(transfer.id());

        for (int i = 0; i < 3; i++) {
            externalTransferService.applyProviderResult(transfer.reference(), GatewayOutcome.SUCCESS, "p-ref", "again");
        }

        assertThat(entryCount(EntryType.EXTERNAL_SETTLEMENT, transfer.reference())).isEqualTo(1);
        assertThat(systemBalance(SystemAccount.PAYSTACK_SETTLEMENT)).isEqualTo(paystackBefore + AMOUNT);
        assertThat(applicationEvents.stream(TransferCompleted.class)
                .filter(event -> event.transferId().equals(transfer.id()))).hasSize(1);
    }

    @Test
    void aRepeatedFailureRefundsOnlyOnce() throws Exception {
        UUID source = newFundedAccount(300_000);
        Started transfer = start(source, "1111", AMOUNT);
        dispatcher.dispatch(transfer.id());

        externalTransferService.applyProviderResult(transfer.reference(), GatewayOutcome.FAILED, null, "again");
        externalTransferService.applyProviderResult(transfer.reference(), GatewayOutcome.REVERSED, null, "again");

        assertThat(balance(source)).isEqualTo(300_000);
        assertThat(entryCount(EntryType.REVERSAL, "release:" + transfer.reference())).isEqualTo(1);
    }

    @Test
    void aLateSuccessForAnAlreadyRefundedTransferIsRaisedForReviewAndMovesNoMoney() throws Exception {
        UUID source = newFundedAccount(300_000);
        Started transfer = start(source, "1111", AMOUNT);
        dispatcher.dispatch(transfer.id());                       // refunded

        assertThatThrownBy(() -> externalTransferService.applyProviderResult(
                transfer.reference(), GatewayOutcome.SUCCESS, "late", "surprise"))
                .isInstanceOfSatisfying(PayBridgeException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_STATE))
                .hasMessageContaining("MANUAL REVIEW");

        assertThat(summary(transfer.id()).status()).isEqualTo(TransferStatus.FAILED);
        assertThat(balance(source)).isEqualTo(300_000);
        assertThat(entryCount(EntryType.EXTERNAL_SETTLEMENT, transfer.reference())).isZero();
    }

    @Test
    void aPendingAnswerChangesNothing() throws Exception {
        UUID source = newFundedAccount(300_000);
        Started transfer = start(source, "1234", AMOUNT);
        dispatcher.dispatch(transfer.id());

        externalTransferService.applyProviderResult(transfer.reference(), GatewayOutcome.PENDING, null, "still working");

        assertThat(summary(transfer.id()).status()).isEqualTo(TransferStatus.PROCESSING);
        assertThat(balance(source)).isEqualTo(300_000 - TOTAL);
    }

    @Test
    void anAnswerForAnUnknownReferenceIsNotFound() {
        assertThatThrownBy(() -> externalTransferService.applyProviderResult(
                "pb-does-not-exist", GatewayOutcome.SUCCESS, null, null))
                .isInstanceOfSatisfying(PayBridgeException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND));
    }

    @Test
    void dispatchingAnAlreadyFinishedTransferDoesNothing() throws Exception {
        UUID source = newFundedAccount(300_000);
        Started transfer = start(source, "3333", AMOUNT);
        dispatcher.dispatch(transfer.id());
        int callsAfterFirst = fakeBank.initiateCalls(transfer.reference());

        dispatcher.dispatch(transfer.id());

        assertThat(fakeBank.initiateCalls(transfer.reference())).isEqualTo(callsAfterFirst);
        assertThat(summary(transfer.id()).status()).isEqualTo(TransferStatus.SUCCESS);
    }

    @Test
    void dispatchDueSendsWhatIsWaiting() throws Exception {
        UUID source = newFundedAccount(300_000);
        Started transfer = start(source, "3333", AMOUNT);

        // other tests may have left transfers in the outbox, so keep going until ours has been picked up
        for (int round = 0; round < 20 && summary(transfer.id()).status() == TransferStatus.PENDING; round++) {
            dispatcher.dispatchDue();
        }

        assertThat(summary(transfer.id()).status()).isEqualTo(TransferStatus.SUCCESS);
    }

    // ---------------------------------------------------------------- 6. racing

    @Test
    void manyWorkersDispatchingTheSameTransferAtOncePayOnlyOnce() throws Exception {
        UUID source = newFundedAccount(300_000);
        Started transfer = start(source, "3333", AMOUNT);
        long paystackBefore = systemBalance(SystemAccount.PAYSTACK_SETTLEMENT);

        runAtTheSameTime(6, () -> {
            dispatcher.dispatch(transfer.id());
            return null;
        });

        assertThat(summary(transfer.id()).status()).isEqualTo(TransferStatus.SUCCESS);
        assertThat(entryCount(EntryType.EXTERNAL_SETTLEMENT, transfer.reference())).isEqualTo(1);
        assertThat(systemBalance(SystemAccount.PAYSTACK_SETTLEMENT)).isEqualTo(paystackBefore + AMOUNT);
        assertThat(balance(source)).isEqualTo(300_000 - TOTAL);
    }

    @Test
    void theSameRequestSentManyTimesAtOnceCreatesOneTransfer() throws Exception {
        UUID source = newFundedAccount(300_000);
        UUID beneficiary = beneficiary(customerOf(source), "3333");
        String key = "race-" + UUID.randomUUID();
        ExternalTransferCommand command = new ExternalTransferCommand(source, beneficiary, AMOUNT, "double click");

        List<TransferResult> results = runAtTheSameTime(8, () -> externalTransferService.initiate(command, key));

        assertThat(results.stream().filter(result -> !result.replayed()).count()).isEqualTo(1);
        assertThat(results.stream().map(result -> result.transfer().id()).distinct().count()).isEqualTo(1);
        assertThat(balance(source)).as("held exactly once").isEqualTo(300_000 - TOTAL);
    }

    private <T> List<T> runAtTheSameTime(int copies, Callable<T> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(copies, 8));
        try {
            CountDownLatch startingGun = new CountDownLatch(1);
            List<Future<T>> futures = new ArrayList<>();
            for (int i = 0; i < copies; i++) {
                futures.add(pool.submit(() -> {
                    startingGun.await();
                    return task.call();
                }));
            }
            startingGun.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get(90, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    // ---------------------------------------------------------------- 7. reading it over HTTP

    @Test
    void theTransferCanBeFollowedOverHttp() throws Exception {
        UUID source = newFundedAccount(300_000);
        Started transfer = start(source, "3333", AMOUNT);

        mockMvc.perform(get("/api/transfers/" + transfer.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"));

        dispatcher.dispatch(transfer.id());

        mockMvc.perform(get("/api/transfers/" + transfer.id()))
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.feeMinor").value(FEE));
        mockMvc.perform(get("/api/transfers").param("accountId", source.toString()))
                .andExpect(jsonPath("$.length()").value(1));
    }
}
