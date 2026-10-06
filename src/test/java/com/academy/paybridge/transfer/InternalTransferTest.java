package com.academy.paybridge.transfer;

import com.academy.paybridge.AbstractIntegrationTest;
import com.academy.paybridge.account.api.AccountApi;
import com.academy.paybridge.compliance.api.ComplianceApi;
import com.academy.paybridge.ledger.api.LedgerApi;
import com.academy.paybridge.transfer.api.TransferCompleted;
import com.academy.paybridge.transfer.api.TransferStatus;
import com.academy.paybridge.transfer.api.TransferType;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.ResultActions;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@RecordApplicationEvents
class InternalTransferTest extends AbstractIntegrationTest {

    @Autowired
    AccountApi accountApi;

    @Autowired
    LedgerApi ledgerApi;

    @Autowired
    ComplianceApi complianceApi;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    ApplicationEvents applicationEvents;

    private static String key() {
        return "key-" + UUID.randomUUID();
    }

    private String numberOf(UUID accountId) {
        return accountApi.getAccount(accountId).accountNumber();
    }

    private long balance(UUID accountId) {
        return accountApi.getAccount(accountId).balanceMinor();
    }

    private static String body(UUID source, String destinationNumber, long amountMinor) {
        return """
                {"sourceAccountId": "%s", "destinationAccountNumber": "%s", "amountMinor": %d, "narration": "rent"}
                """.formatted(source, destinationNumber, amountMinor);
    }

    private ResultActions send(String idempotencyKey, UUID source, String destinationNumber, long amountMinor)
            throws Exception {
        var request = post("/api/transfers/internal")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(source, destinationNumber, amountMinor));
        if (idempotencyKey != null) {
            request.header("Idempotency-Key", idempotencyKey);
        }
        return mockMvc.perform(request);
    }

    // ---------- success ----------

    @Test
    void movesTheMoneyAndReportsSuccess() throws Exception {
        UUID from = newFundedAccount(20_000);
        UUID to = newAccount();

        String json = send(key(), from, numberOf(to), 5_000)
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.type").value("INTERNAL"))
                .andExpect(jsonPath("$.amountMinor").value(5_000))
                .andExpect(jsonPath("$.feeMinor").value(0))
                .andExpect(jsonPath("$.reference").value(org.hamcrest.Matchers.startsWith("pb-")))
                .andReturn().getResponse().getContentAsString();

        assertThat(balance(from)).isEqualTo(15_000);
        assertThat(balance(to)).isEqualTo(5_000);

        String id = JsonPath.read(json, "$.id");
        mockMvc.perform(get("/api/transfers/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"));
    }

    @Test
    void theTransferShowsUpInBothAccountsHistoryAndLedger() throws Exception {
        UUID from = newFundedAccount(20_000);
        UUID to = newAccount();
        send(key(), from, numberOf(to), 5_000).andExpect(status().isCreated());

        mockMvc.perform(get("/api/transfers").param("accountId", from.toString()))
                .andExpect(jsonPath("$.length()").value(1));
        mockMvc.perform(get("/api/transfers").param("accountId", to.toString()))
                .andExpect(jsonPath("$.length()").value(1));
        mockMvc.perform(get("/api/accounts/" + to + "/entries"))
                .andExpect(jsonPath("$[0].amountMinor").value(5_000))
                .andExpect(jsonPath("$[0].type").value("TRANSFER"));
    }

    @Test
    void announcesACompletedTransferToTheRestOfTheSystem() throws Exception {
        UUID from = newFundedAccount(20_000);
        UUID to = newAccount();

        send(key(), from, numberOf(to), 5_000).andExpect(status().isCreated());

        assertThat(applicationEvents.stream(TransferCompleted.class)
                .filter(event -> event.sourceAccountId().equals(from)))
                .singleElement()
                .satisfies(event -> {
                    assertThat(event.destinationAccountId()).isEqualTo(to);
                    assertThat(event.amountMinor()).isEqualTo(5_000);
                    assertThat(event.type()).isEqualTo(TransferType.INTERNAL);
                });
    }

    // ---------- retries (idempotency) ----------

    @Test
    void aRetryWithTheSameKeyReturnsTheSameTransferAndMovesMoneyOnce() throws Exception {
        UUID from = newFundedAccount(20_000);
        UUID to = newAccount();
        String key = key();

        String first = send(key, from, numberOf(to), 5_000)
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String second = send(key, from, numberOf(to), 5_000)
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andReturn().getResponse().getContentAsString();

        assertThat((String) JsonPath.read(second, "$.id")).isEqualTo(JsonPath.read(first, "$.id"));
        assertThat(balance(from)).isEqualTo(15_000);
        assertThat(balance(to)).isEqualTo(5_000);
        assertThat(applicationEvents.stream(TransferCompleted.class)
                .filter(event -> event.sourceAccountId().equals(from))).hasSize(1);
    }

    @Test
    void theSameKeyForADifferentRequestIsRefused() throws Exception {
        UUID from = newFundedAccount(20_000);
        UUID to = newAccount();
        String key = key();
        send(key, from, numberOf(to), 5_000).andExpect(status().isCreated());

        send(key, from, numberOf(to), 6_000)
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
        assertThat(balance(from)).isEqualTo(15_000);
    }

    @Test
    void theIdempotencyKeyIsRequired() throws Exception {
        UUID from = newFundedAccount(1_000);
        UUID to = newAccount();

        send(null, from, numberOf(to), 100)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("Idempotency-Key")));
        assertThat(balance(from)).isEqualTo(1_000);
    }

    @Test
    void anIdempotencyKeyWithOddCharactersIsRefused() throws Exception {
        UUID from = newFundedAccount(1_000);
        UUID to = newAccount();

        send("short", from, numberOf(to), 100).andExpect(status().isBadRequest());
        send("has spaces in it!", from, numberOf(to), 100).andExpect(status().isBadRequest());
    }

    @Test
    void aFailedAttemptDoesNotBurnTheKey() throws Exception {
        UUID from = newFundedAccount(1_000);
        UUID to = newAccount();
        String key = key();

        send(key, from, numberOf(to), 5_000)
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_FUNDS"));

        fundAccount(from, 10_000);
        send(key, from, numberOf(to), 5_000).andExpect(status().isCreated());

        assertThat(balance(to)).isEqualTo(5_000);
    }

    // ---------- refusals leave no trace ----------

    @Test
    void anOverdraftIsRefusedAndNothingMoves() throws Exception {
        UUID from = newFundedAccount(1_000);
        UUID to = newAccount();

        send(key(), from, numberOf(to), 1_001)
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_FUNDS"));

        assertThat(balance(from)).isEqualTo(1_000);
        assertThat(balance(to)).isZero();
        assertThat(complianceApi.limitStatus(accountApi.getAccount(from).ownerCustomerId()).usedTodayMinor())
                .as("a refused transfer must not use up the daily allowance")
                .isZero();
    }

    @Test
    void anUnknownDestinationIsNotFound() throws Exception {
        UUID from = newFundedAccount(1_000);

        send(key(), from, "0000000000", 100)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    void anUnknownSourceIsNotFound() throws Exception {
        UUID to = newAccount();

        send(key(), UUID.randomUUID(), numberOf(to), 100).andExpect(status().isNotFound());
    }

    @Test
    void youCannotSendToYourself() throws Exception {
        UUID account = newFundedAccount(1_000);

        send(key(), account, numberOf(account), 100)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void badAmountsAndAccountNumbersAreRejectedByValidation() throws Exception {
        UUID from = newFundedAccount(1_000);
        UUID to = newAccount();

        send(key(), from, numberOf(to), 0)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.amountMinor").exists());
        send(key(), from, numberOf(to), -5)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.amountMinor").exists());
        send(key(), from, "12345", 100)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.destinationAccountNumber").exists());
    }

    @Test
    void aFrozenSourceAccountCannotSend() throws Exception {
        UUID from = newFundedAccount(1_000);
        UUID to = newAccount();
        jdbcTemplate.update("update account set status = 'FROZEN' where id = ?", from);

        send(key(), from, numberOf(to), 100)
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.code").value("ACCOUNT_NOT_ACTIVE"));
    }

    @Test
    void aFrozenDestinationAccountCannotReceive() throws Exception {
        UUID from = newFundedAccount(1_000);
        UUID to = newAccount();
        jdbcTemplate.update("update account set status = 'FROZEN' where id = ?", to);

        send(key(), from, numberOf(to), 100)
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.code").value("ACCOUNT_NOT_ACTIVE"));
        assertThat(balance(from)).isEqualTo(1_000);
    }

    @Test
    void aSystemAccountCannotBeUsedAsASource() throws Exception {
        UUID to = newAccount();
        UUID suspense = accountApi.systemAccountId(com.academy.paybridge.account.api.SystemAccount.SUSPENSE);

        send(key(), suspense, numberOf(to), 100)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    // ---------- compliance limits ----------

    @Test
    void aTransferOverTheCustomersLimitIsRefused() throws Exception {
        UUID from = newFundedAccount(9_000_000);   // NGN 90,000
        UUID to = newAccount();

        send(key(), from, numberOf(to), 5_000_001) // just over the NGN 50,000 UNVERIFIED limit
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.code").value("LIMIT_EXCEEDED"));
        assertThat(balance(from)).isEqualTo(9_000_000);
    }

    @Test
    void successfulTransfersUseUpTheDailyAllowance() throws Exception {
        UUID from = newFundedAccount(9_000_000);
        UUID to = newAccount();
        UUID customer = accountApi.getAccount(from).ownerCustomerId();

        send(key(), from, numberOf(to), 4_000_000).andExpect(status().isCreated());

        assertThat(complianceApi.limitStatus(customer).usedTodayMinor()).isEqualTo(4_000_000);
    }

    @Test
    void whenTheDailyLimitIsUsedUpTheNextTransferIsRefused() throws Exception {
        UUID from = newFundedAccount(20_000_000);
        UUID to = newAccount();

        send(key(), from, numberOf(to), 5_000_000).andExpect(status().isCreated());
        send(key(), from, numberOf(to), 5_000_000).andExpect(status().isCreated()); // now at the 100,000 daily limit
        send(key(), from, numberOf(to), 1)
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.code").value("LIMIT_EXCEEDED"));

        assertThat(balance(to)).isEqualTo(10_000_000);
    }

    // ---------- reading ----------

    @Test
    void anUnknownTransferIsNotFound() throws Exception {
        mockMvc.perform(get("/api/transfers/" + UUID.randomUUID())).andExpect(status().isNotFound());
    }

    @Test
    void theTransferRecordMatchesWhatHappened() throws Exception {
        UUID from = newFundedAccount(5_000);
        UUID to = newAccount();
        String json = send(key(), from, numberOf(to), 5_000).andReturn().getResponse().getContentAsString();

        Integer count = jdbcTemplate.queryForObject(
                "select count(*) from transfer where id = ?::uuid and status = ? and amount_minor = 5000",
                Integer.class, JsonPath.read(json, "$.id"), TransferStatus.SUCCESS.name());
        assertThat(count).isEqualTo(1);
        assertThat(ledgerApi.sumOfLines(from)).isEqualTo(balance(from));
    }
}
