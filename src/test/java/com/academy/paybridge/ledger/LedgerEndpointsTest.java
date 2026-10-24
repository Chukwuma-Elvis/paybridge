package com.academy.paybridge.ledger;

import com.academy.paybridge.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class LedgerEndpointsTest extends AbstractIntegrationTest {

    @Test
    void devFundingPutsMoneyInAnAccount() throws Exception {
        UUID account = newAccount();

        mockMvc.perform(post("/api/dev/accounts/" + account + "/fund")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amountMinor\": 5000}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balanceMinor").value(5000))
                .andExpect(jsonPath("$.entryId").exists());
    }

    @Test
    void devFundingRejectsANonPositiveAmount() throws Exception {
        UUID account = newAccount();

        mockMvc.perform(post("/api/dev/accounts/" + account + "/fund")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amountMinor\": 0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.amountMinor").exists());
    }

    @Test
    void devFundingRejectsAnUnknownAccount() throws Exception {
        mockMvc.perform(post("/api/dev/accounts/" + UUID.randomUUID() + "/fund")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amountMinor\": 100}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void theStatementShowsTheMovementsNewestFirst() throws Exception {
        UUID account = newAccount();
        fundAccount(account, 1_000);
        fundAccount(account, 2_500);

        mockMvc.perform(get("/api/accounts/" + account + "/entries"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].amountMinor").value(2500))
                .andExpect(jsonPath("$[0].type").value("FUNDING"))
                .andExpect(jsonPath("$[1].amountMinor").value(1000));
    }

    @Test
    void theStatementOfAnUnknownAccountIsNotFound() throws Exception {
        mockMvc.perform(get("/api/accounts/" + UUID.randomUUID() + "/entries"))
                .andExpect(status().isNotFound());
    }

    @Test
    void devResetPurgesAccountsAndKeys() throws Exception {
        UUID account = newAccount();
        fundAccount(account, 1_000);

        mockMvc.perform(post("/api/dev/reset"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"));
    }
}
