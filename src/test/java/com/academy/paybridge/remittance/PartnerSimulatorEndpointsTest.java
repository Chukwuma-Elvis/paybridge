package com.academy.paybridge.remittance;

import com.academy.paybridge.remittance.api.RemittanceStatus;
import com.academy.paybridge.remittance.api.RemittanceView;
import com.academy.paybridge.security.domain.ApiKeyRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Step 31 tests: Admin partner simulator triggers signed funding webhook and processor advancement.
 */
class PartnerSimulatorEndpointsTest extends AbstractRemittanceTest {

    private static final String KEY = "X-API-Key";

    private MockMvc mvc;
    private String client;
    private String admin;

    @BeforeEach
    void keys() {
        mvc = secured();
        client = newKey(ApiKeyRole.CLIENT);
        admin = newKey(ApiKeyRole.ADMIN);
    }

    private RemittanceView awaiting(String accountNumber) throws Exception {
        RemittanceView view = createClean(paybridgeRecipient(accountNumber));
        assertThat(view.status()).isEqualTo(RemittanceStatus.AWAITING_FUNDING);
        return view;
    }

    @Test
    void clientRoleIsForbidden() throws Exception {
        RemittanceView view = awaiting(newAccountNumber());

        String body = """
                {"remittanceReference":"%s","amountMinor":%d,"mode":"valid"}
                """.formatted(view.reference(), SELL);

        mvc.perform(post("/api/admin/simulator/partner-funding")
                        .header(KEY, client)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden());
    }

    @Test
    void forgedModeIsRefusedWith401Unauthorized() throws Exception {
        RemittanceView view = awaiting(newAccountNumber());

        String body = """
                {"remittanceReference":"%s","amountMinor":%d,"mode":"forged"}
                """.formatted(view.reference(), SELL);

        mvc.perform(post("/api/admin/simulator/partner-funding")
                        .header(KEY, admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void validModeReachesPaidAndCreditsRecipientAccount() throws Exception {
        String recipientAccount = newAccountNumber();
        long initialBalance = balanceOfNumber(recipientAccount);
        RemittanceView view = awaiting(recipientAccount);

        String body = """
                {"remittanceReference":"%s","amountMinor":%d,"mode":"valid"}
                """.formatted(view.reference(), SELL);

        mvc.perform(post("/api/admin/simulator/partner-funding")
                        .header(KEY, admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPLIED"))
                .andExpect(jsonPath("$.mode").value("valid"));

        // Verify remittance is now PAID
        assertThat(statusOf(view)).isEqualTo(RemittanceStatus.PAID);

        // Verify recipient's account balance increased by buyMinor in NGN
        long finalBalance = balanceOfNumber(recipientAccount);
        assertThat(finalBalance).isEqualTo(initialBalance + view.buyMinor());
    }

    @Test
    void repeatModeReturnsDuplicate() throws Exception {
        RemittanceView view = awaiting(newAccountNumber());

        String body = """
                {"remittanceReference":"%s","amountMinor":%d,"mode":"repeat","eventId":"fixed-event-id-123"}
                """.formatted(view.reference(), SELL);

        mvc.perform(post("/api/admin/simulator/partner-funding")
                        .header(KEY, admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DUPLICATE"));
    }

    @Test
    void wrongAmountRecordsMismatch() throws Exception {
        RemittanceView view = awaiting(newAccountNumber());

        String body = """
                {"remittanceReference":"%s","amountMinor":50,"mode":"wrong-amount"}
                """.formatted(view.reference());

        mvc.perform(post("/api/admin/simulator/partner-funding")
                        .header(KEY, admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("AMOUNT_MISMATCH"));
    }
}
