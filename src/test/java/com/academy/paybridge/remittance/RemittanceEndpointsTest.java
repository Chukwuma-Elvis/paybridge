package com.academy.paybridge.remittance;

import com.academy.paybridge.notification.domain.Notification;
import com.academy.paybridge.notification.service.NotificationService;
import com.academy.paybridge.remittance.api.RemittanceStatus;
import com.academy.paybridge.remittance.api.RemittanceView;
import com.academy.paybridge.security.domain.ApiKeyRole;
import com.academy.paybridge.security.service.RateLimiter;
import com.jayway.jsonpath.JsonPath;
import io.micrometer.core.instrument.Counter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Step 23: the remittance endpoints meet the same security and observability bar as transfers. */
class RemittanceEndpointsTest extends AbstractRemittanceTest {

    private static final String KEY = "X-API-Key";

    @Autowired NotificationService notifications;

    private MockMvc mvc;
    private String client;
    private String admin;

    @BeforeEach
    void keys() {
        mvc = secured();
        client = newKey(ApiKeyRole.CLIENT);
        admin = newKey(ApiKeyRole.ADMIN);
    }

    private String body(String quoteId, String senderName, String accountNumber) {
        return """
                {"partnerCode":"fakepartner","partnerReference":"pref-%s","quoteId":"%s",
                 "sender":{"reference":"sender-%s","fullName":"%s","country":"NG","dateOfBirth":"1990-05-05"},
                 "recipient":{"type":"PAYBRIDGE","fullName":"Ada Obi","country":"NG","accountNumber":"%s"}}
                """.formatted(UUID.randomUUID(), quoteId, UUID.randomUUID(), senderName, accountNumber);
    }

    private double counter(String to) {
        Counter c = meters.find("paybridge.remittance.transitions").tag("to", to).counter();
        return c == null ? 0 : c.count();
    }

    // ---- who may do what

    @Test
    void noKeyMeansNoAccess() throws Exception {
        mvc.perform(post("/api/remittances").contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/remittances/" + UUID.randomUUID())).andExpect(status().isUnauthorized());
    }

    @Test
    void aClientCanCreateAndReadButNotUseTheAdminDesk() throws Exception {
        String quote = newQuote(SELL).id().toString();
        String account = newAccountNumber();

        String created = mvc.perform(post("/api/remittances").header(KEY, client).header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON).content(body(quote, "Chidi Okafor", account)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("AWAITING_FUNDING"))
                .andExpect(jsonPath("$.buyMinor").value(BUY))
                .andExpect(content().string(not(containsString(account))))        // only the masked number is ever shown
                .andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(created, "$.id");

        mvc.perform(get("/api/remittances/" + id).header(KEY, client)).andExpect(status().isOk());
        mvc.perform(get("/api/admin/remittances/" + id + "/history").header(KEY, client)).andExpect(status().isForbidden());
        mvc.perform(post("/api/admin/remittances/process").header(KEY, client)).andExpect(status().isForbidden());
        mvc.perform(post("/api/admin/remittances/" + id + "/review").header(KEY, client).contentType(MediaType.APPLICATION_JSON)
                .content("{\"decision\":\"APPROVE\",\"reason\":\"x\"}")).andExpect(status().isForbidden());
    }

    @Test
    void anAdminCanReadTheHistoryAndRunTheEngine() throws Exception {
        RemittanceView view = createClean(paybridgeRecipient(newAccountNumber()));

        mvc.perform(get("/api/admin/remittances/" + view.id() + "/history").header(KEY, admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0]").value("- -> CREATED"));
        mvc.perform(post("/api/admin/remittances/process").header(KEY, admin))
                .andExpect(status().isOk()).andExpect(jsonPath("$.handled").exists());
        mvc.perform(get("/api/admin/remittances/" + UUID.randomUUID() + "/history").header(KEY, admin)).andExpect(status().isNotFound());
    }

    @Test
    void anAdminDecidesAReviewOverHttpWithAMandatoryReason() throws Exception {
        String quote = newQuote(SELL).id().toString();
        String created = mvc.perform(post("/api/remittances").header(KEY, client).header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON).content(body(quote, "Alina Samplova", newAccountNumber())))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("UNDER_REVIEW"))
                .andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(created, "$.id");

        mvc.perform(post("/api/admin/remittances/" + id + "/review").header(KEY, admin).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"APPROVE\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.reason").exists());
        mvc.perform(post("/api/admin/remittances/" + id + "/review").header(KEY, admin).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"APPROVE\",\"reason\":\"Birth dates differ\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("AWAITING_FUNDING"));
    }

    // ---- the request itself

    @Test
    void theIdempotencyKeyIsMandatoryAndAReplayReturnsTheSameRemittance() throws Exception {
        String quote = newQuote(SELL).id().toString();
        String json = body(quote, "Chidi Okafor", newAccountNumber());
        String idem = key();

        mvc.perform(post("/api/remittances").header(KEY, client).contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isBadRequest());
        String first = mvc.perform(post("/api/remittances").header(KEY, client).header("Idempotency-Key", idem)
                        .contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        mvc.perform(post("/api/remittances").header(KEY, client).header("Idempotency-Key", idem)
                        .contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value((String) JsonPath.read(first, "$.id")));
    }

    @Test
    void badInputGetsTheStandardErrorShape() throws Exception {
        mvc.perform(post("/api/remittances").header(KEY, client).header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"partnerCode\":\"\",\"sender\":{\"reference\":\"s\"},\"recipient\":{\"accountNumber\":\"12\"}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors").exists());
    }

    @Test
    void anExpiredQuoteIsAnUnprocessableRequestNotAServerError() throws Exception {
        UUID quote = newQuote(SELL).id();
        jdbc.update("update fx_quote set expires_at = now() - interval '1 second' where id = ?", quote);

        mvc.perform(post("/api/remittances").header(KEY, client).header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON).content(body(quote.toString(), "Chidi Okafor", newAccountNumber())))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("QUOTE_EXPIRED"));
    }

    @Test
    void aSendersRemittancesCanBeListed() throws Exception {
        String quote = newQuote(SELL).id().toString();
        String json = body(quote, "Chidi Okafor", newAccountNumber());
        String senderRef = JsonPath.read(json, "$.sender.reference");
        mvc.perform(post("/api/remittances").header(KEY, client).header("Idempotency-Key", key())
                .contentType(MediaType.APPLICATION_JSON).content(json)).andExpect(status().isCreated());

        mvc.perform(get("/api/remittances").header(KEY, client).param("partnerCode", "fakepartner").param("senderReference", senderRef))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1));
    }

    // ---- rate limits

    @Test
    void creatingQuotesAndRemittancesIsRateLimitedButReadingIsNot() throws Exception {
        MockMvc limited = secured(properties(true), new RateLimiter(1_000, clock), new RateLimiter(1_000, clock),
                new RateLimiter(2, clock));
        String key = newKey(ApiKeyRole.CLIENT);
        String quoteBody = "{\"corridor\":\"USD_NGN\",\"sellMinor\":100000}";

        limited.perform(post("/api/fx/quotes").header(KEY, key).header("Idempotency-Key", key()).contentType(MediaType.APPLICATION_JSON)
                .content(quoteBody)).andExpect(status().isCreated());
        limited.perform(post("/api/fx/quotes").header(KEY, key).header("Idempotency-Key", key()).contentType(MediaType.APPLICATION_JSON)
                .content(quoteBody)).andExpect(status().isCreated());
        limited.perform(post("/api/fx/quotes").header(KEY, key).header("Idempotency-Key", key()).contentType(MediaType.APPLICATION_JSON)
                .content(quoteBody)).andExpect(status().isTooManyRequests());
        limited.perform(post("/api/remittances").header(KEY, key).header("Idempotency-Key", key()).contentType(MediaType.APPLICATION_JSON)
                .content("{}")).andExpect(status().isTooManyRequests());
        limited.perform(get("/api/fx/rates/USD_NGN").header(KEY, key)).andExpect(status().isOk());
    }

    // ---- notifications and metrics

    @Test
    void thePayBridgeRecipientIsToldWithoutAnAccountNumberInTheMessage() throws Exception {
        String account = newAccountNumber();
        UUID customer = accounts.getByAccountNumber(account).ownerCustomerId();
        RemittanceView view = createClean(paybridgeRecipient(account));
        fundingWebhook(view, SELL);
        runEngine();
        assertThat(statusOf(view)).isEqualTo(RemittanceStatus.PAID);

        List<Notification> sent = List.of();
        for (int i = 0; i < 50 && sent.isEmpty(); i++) {
            sent = notifications.listForCustomer(customer, 10);
            if (sent.isEmpty()) {
                Thread.sleep(100);
            }
        }

        assertThat(sent).isNotEmpty();
        assertThat(sent).allSatisfy(n -> {
            assertThat(n.getMessage()).contains("NGN 1,477,575.00").contains(view.reference());
            assertThat(n.getMessage()).doesNotContain(account);
        });
    }

    @Test
    void transitionsAreCountedAfterCommitAndARolledBackCreationIsNotCounted() throws Exception {
        double createdBefore = counter("created");
        double awaitingBefore = counter("awaiting_funding");
        var tooBig = newQuote(600_000);                       // over the per-payment limit, so creation is refused and rolled back
        mvc.perform(post("/api/remittances").header(KEY, client).header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON).content(body(tooBig.id().toString(), "Chidi Okafor", newAccountNumber())))
                .andExpect(status().isUnprocessableEntity());
        assertThat(counter("created")).as("nothing counted for the refused creation").isEqualTo(createdBefore);

        createClean(paybridgeRecipient(newAccountNumber()));

        assertThat(counter("created")).isEqualTo(createdBefore + 1);
        assertThat(counter("awaiting_funding")).isEqualTo(awaitingBefore + 1);
    }
}
