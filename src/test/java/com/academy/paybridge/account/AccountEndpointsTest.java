package com.academy.paybridge.account;

import com.academy.paybridge.AbstractIntegrationTest;
import com.academy.paybridge.account.api.AccountApi;
import com.academy.paybridge.account.api.AccountSummary;
import com.academy.paybridge.account.api.InsufficientFundsException;
import com.academy.paybridge.account.api.SystemAccount;
import com.academy.paybridge.account.domain.Nuban;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AccountEndpointsTest extends AbstractIntegrationTest {

    @Autowired
    AccountApi accountApi;

    @Autowired
    TransactionTemplate transactionTemplate;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Test
    void opensAnEmptyNgnAccountWithAValidNubanNumber() throws Exception {
        UUID customerId = createCustomer();

        String json = mockMvc.perform(post("/api/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customerId\": \"" + customerId + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.customerId").value(customerId.toString()))
                .andExpect(jsonPath("$.currency").value("NGN"))
                .andExpect(jsonPath("$.balanceMinor").value(0))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andReturn().getResponse().getContentAsString();

        String accountNumber = JsonPath.read(json, "$.accountNumber");
        assertThat(Nuban.isValid("999", accountNumber)).isTrue();
    }

    @Test
    void everyAccountGetsADifferentNumber() throws Exception {
        UUID first = newAccount();
        UUID second = newAccount();

        assertThat(accountApi.getAccount(first).accountNumber())
                .isNotEqualTo(accountApi.getAccount(second).accountNumber());
    }

    @Test
    void refusesToOpenAnAccountForAnUnknownCustomer() throws Exception {
        mockMvc.perform(post("/api/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customerId\": \"" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    void refusesACurrencyWeDoNotSupportYet() throws Exception {
        UUID customerId = createCustomer();

        mockMvc.perform(post("/api/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customerId\": \"" + customerId + "\", \"currency\": \"USD\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void requiresACustomerId() throws Exception {
        mockMvc.perform(post("/api/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors.customerId").exists());
    }

    @Test
    void fetchesAnAccountByIdAndByNumber() throws Exception {
        UUID accountId = newAccount();
        String number = accountApi.getAccount(accountId).accountNumber();

        mockMvc.perform(get("/api/accounts/" + accountId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountNumber").value(number));

        mockMvc.perform(get("/api/accounts/by-number/" + number))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(accountId.toString()));
    }

    @Test
    void listsTheAccountsOfACustomer() throws Exception {
        UUID customerId = createCustomer();
        openAccount(customerId);
        openAccount(customerId);

        mockMvc.perform(get("/api/accounts").param("customerId", customerId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void theBanksInternalAccountsExist() {
        for (SystemAccount systemAccount : SystemAccount.values()) {
            AccountSummary summary = accountApi.getAccount(accountApi.systemAccountId(systemAccount));

            assertThat(summary.code()).isEqualTo(systemAccount.name());
            assertThat(summary.accountNumber()).isNull();
            assertThat(summary.isCustomerAccount()).isFalse();
        }
    }

    @Test
    void aCustomerAccountCannotBeDebitedBelowZero() throws Exception {
        UUID accountId = newAccount();

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(tx -> {
            accountApi.lockAccounts(List.of(accountId));
            accountApi.applyBalanceChange(accountId, -1);
        })).isInstanceOf(InsufficientFundsException.class);
    }

    @Test
    void aCreditCanBeSpentButNotMoreThanTheBalance() throws Exception {
        UUID accountId = newAccount();

        transactionTemplate.executeWithoutResult(tx -> {
            accountApi.lockAccounts(List.of(accountId));
            accountApi.applyBalanceChange(accountId, 5_000);
            accountApi.applyBalanceChange(accountId, -3_000);
        });
        assertThat(accountApi.getAccount(accountId).balanceMinor()).isEqualTo(2_000);

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(tx -> {
            accountApi.lockAccounts(List.of(accountId));
            accountApi.applyBalanceChange(accountId, -2_001);
        })).isInstanceOf(InsufficientFundsException.class);
        assertThat(accountApi.getAccount(accountId).balanceMinor()).isEqualTo(2_000);

        // This test moves balances directly, bypassing the ledger, so put everything back to zero
        // to keep the "all balances add up to zero" rule true for every other test.
        transactionTemplate.executeWithoutResult(tx -> {
            accountApi.lockAccounts(List.of(accountId));
            accountApi.applyBalanceChange(accountId, -2_000);
        });
        assertThat(accountApi.getAccount(accountId).balanceMinor()).isZero();
    }

    @Test
    void systemAccountsMayGoNegative() {
        UUID funding = accountApi.systemAccountId(SystemAccount.FUNDING_SOURCE);
        long before = accountApi.getAccount(funding).balanceMinor();

        transactionTemplate.executeWithoutResult(tx -> {
            accountApi.lockAccounts(List.of(funding));
            accountApi.applyBalanceChange(funding, -1_000_000_000_000L);
            accountApi.applyBalanceChange(funding, 1_000_000_000_000L);
        });

        assertThat(accountApi.getAccount(funding).balanceMinor()).isEqualTo(before);
    }

    @Test
    void balanceChangesOutsideATransactionAreRefused() throws Exception {
        UUID accountId = newAccount();

        assertThatThrownBy(() -> accountApi.applyBalanceChange(accountId, 100))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void theDatabaseItselfRefusesANegativeCustomerBalance() throws Exception {
        UUID accountId = newAccount();

        assertThatThrownBy(() -> jdbcTemplate.update(
                "update account set balance_minor = -1 where id = ?", accountId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
