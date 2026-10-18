package com.academy.paybridge.settlement;

import com.academy.paybridge.security.domain.ApiKeyRole;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The settlement desk over HTTP, through the real security filters. */
class SettlementEndpointsTest extends AbstractSettlementTest {

    private static final String KEY = "X-API-Key";
    private static final MediaType CSV = MediaType.parseMediaType("text/csv");

    private MockMvc mvc;
    private String admin;
    private String client;

    @BeforeEach
    void keys() {
        mvc = secured();
        admin = newKey(ApiKeyRole.ADMIN);
        client = newKey(ApiKeyRole.CLIENT);
    }

    @Test
    void onlyAnAdminMayUseTheSettlementDesk() throws Exception {
        mvc.perform(get("/api/admin/settlement/partners/fakepartner/position").header(KEY, client)).andExpect(status().isForbidden());
        mvc.perform(post("/api/admin/settlement/partners/fakepartner/close").header(KEY, client)).andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/settlement/batches")).andExpect(status().isUnauthorized());
    }

    @Test
    void anAdminClosesLoadsTheStatementAndResolvesOverHttp() throws Exception {
        funded(100_000);
        String closed = mvc.perform(post("/api/admin/settlement/partners/fakepartner/close").header(KEY, admin))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CLOSED")).andExpect(jsonPath("$.lineCount").value(1))
                .andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(closed, "$.id");
        mvc.perform(post("/api/admin/settlement/partners/fakepartner/close").header(KEY, admin)).andExpect(status().isNoContent());

        String csv = statementOf(batches.lines(java.util.UUID.fromString(id)), -500);
        mvc.perform(post("/api/admin/settlement/batches/" + id + "/statement").header(KEY, admin).contentType(CSV).content(csv))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.amountDiffers").value(1)).andExpect(jsonPath("$.breaksOpen").value(1));
        mvc.perform(post("/api/admin/settlement/batches/" + id + "/statement").header(KEY, admin).contentType(CSV).content(csv))
                .andExpect(status().isOk()).andExpect(jsonPath("$.duplicate").value(true));

        String listing = mvc.perform(get("/api/admin/settlement/batches/" + id + "/breaks").header(KEY, admin).param("status", "OPEN"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String breakId = JsonPath.read(listing, "$[0].id");
        mvc.perform(post("/api/admin/settlement/breaks/" + breakId + "/resolve").header(KEY, admin).contentType(MediaType.APPLICATION_JSON)
                .content("{\"adjust\":false}")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.note").exists());
        mvc.perform(post("/api/admin/settlement/breaks/" + breakId + "/resolve").header(KEY, admin).contentType(MediaType.APPLICATION_JSON)
                .content("{\"note\":\"Agreed with the partner\",\"adjust\":true}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("RESOLVED"));

        mvc.perform(post("/api/admin/settlement/batches/" + id + "/instruct").header(KEY, admin)).andExpect(status().isOk());
        mvc.perform(post("/api/admin/settlement/batches/" + id + "/confirm").header(KEY, admin))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("RECONCILED"));
        mvc.perform(get("/api/admin/settlement/batches/" + id + "/report").header(KEY, admin))
                .andExpect(status().isOk()).andExpect(jsonPath("$.resolvedBreaks").value(1));
    }

    @Test
    void aMalformedStatementIsABadRequestAndAnOversizedOneIsTooLarge() throws Exception {
        funded(100_000);
        String id = JsonPath.read(mvc.perform(post("/api/admin/settlement/partners/fakepartner/close").header(KEY, admin))
                .andReturn().getResponse().getContentAsString(), "$.id");

        mvc.perform(post("/api/admin/settlement/batches/" + id + "/statement").header(KEY, admin).contentType(CSV)
                .content("reference,amount,currency,date\nRM-1,abc,USD,2026-10-14\n")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/admin/settlement/batches/" + id + "/statement").header(KEY, admin).contentType(CSV)
                .content(new byte[1_048_577])).andExpect(status().isPayloadTooLarge());
    }

    @Test
    void anUnknownBatchOrPartnerIsNotFound() throws Exception {
        mvc.perform(get("/api/admin/settlement/batches/" + java.util.UUID.randomUUID()).header(KEY, admin)).andExpect(status().isNotFound());
        mvc.perform(get("/api/admin/settlement/partners/nobody/position").header(KEY, admin)).andExpect(status().isNotFound());
    }
}
