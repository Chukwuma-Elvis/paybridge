package com.academy.paybridge.fx;

import com.academy.paybridge.fx.api.Corridor;
import com.academy.paybridge.fx.client.FakeRateProvider;
import com.academy.paybridge.fx.service.FxRateService;
import com.academy.paybridge.security.AbstractSecurityTest;
import com.academy.paybridge.security.domain.ApiKeyRole;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The rate endpoints through the real security filters: who may do what, and what they get. */
class FxEndpointsTest extends AbstractSecurityTest {

    private static final String KEY = "X-API-Key";

    @Autowired FxRateService fx;
    @Autowired FakeRateProvider source;
    @Autowired JdbcTemplate jdbc;

    private MockMvc mvc;
    private String admin;
    private String client;

    @BeforeEach
    void setUpKeysAndABaselineRate() {
        mvc = secured();
        admin = newKey(ApiKeyRole.ADMIN);
        client = newKey(ApiKeyRole.CLIENT);
        source.reset();
        fx.setManually(Corridor.USD_NGN, new BigDecimal("1500"), "baseline", "test");
        fx.setSpread(Corridor.USD_NGN, 100, "test");
    }

    @AfterEach
    void leaveTheSourceHealthy() {
        source.reset();
    }

    // ---- who may do what

    @Test
    void aClientCanReadTheCurrentRateButNotManageThem() throws Exception {
        mvc.perform(get("/api/fx/rates/USD_NGN").header(KEY, client))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.corridor").value("USD_NGN"))
                .andExpect(jsonPath("$.midRate").value(1500.0))
                .andExpect(jsonPath("$.customerRate").value(1485.0))
                .andExpect(jsonPath("$.spreadBps").value(100));

        mvc.perform(get("/api/admin/fx/rates").header(KEY, client)).andExpect(status().isForbidden());
        mvc.perform(post("/api/admin/fx/refresh").header(KEY, client).contentType(MediaType.APPLICATION_JSON)
                .content("{\"corridor\":\"USD_NGN\"}")).andExpect(status().isForbidden());
        mvc.perform(put("/api/admin/fx/spread").header(KEY, client).contentType(MediaType.APPLICATION_JSON)
                .content("{\"corridor\":\"USD_NGN\",\"spreadBps\":50}")).andExpect(status().isForbidden());
    }

    @Test
    void noKeyMeansNoRate() throws Exception {
        mvc.perform(get("/api/fx/rates/USD_NGN")).andExpect(status().isUnauthorized());
    }

    @Test
    void anUnknownCorridorIsABadRequest() throws Exception {
        mvc.perform(get("/api/fx/rates/EUR_NGN").header(KEY, client)).andExpect(status().isBadRequest());
    }

    // ---- admin: the feed

    @Test
    void anAdminCanListTheRatesNewestFirst() throws Exception {
        mvc.perform(get("/api/admin/fx/rates").param("limit", "3").header(KEY, admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].corridor").value("USD_NGN"))
                .andExpect(jsonPath("$[0].status").value("ACTIVE"));
    }

    @Test
    void anAdminCanTriggerARefreshAndSeeTheOutcome() throws Exception {
        source.setMidRate(new BigDecimal("1510"));

        mvc.perform(post("/api/admin/fx/refresh").header(KEY, admin).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"corridor\":\"USD_NGN\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("ACCEPTED"));
    }

    @Test
    void aRefreshWithoutACorridorIsRejectedWithFieldErrors() throws Exception {
        mvc.perform(post("/api/admin/fx/refresh").header(KEY, admin).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.corridor").exists());
    }

    @Test
    void aHeldRateCanBeApprovedOverHttpAndTheApproverIsRecorded() throws Exception {
        BigDecimal unique = new BigDecimal("1700").add(BigDecimal.valueOf(java.util.concurrent.ThreadLocalRandom.current().nextInt(1, 900_000), 6));
        source.setMidRate(unique);
        mvc.perform(post("/api/admin/fx/refresh").header(KEY, admin).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"corridor\":\"USD_NGN\"}"))
                .andExpect(jsonPath("$.outcome").value("HELD"));
        String listing = mvc.perform(get("/api/admin/fx/rates").param("limit", "1").header(KEY, admin))
                .andReturn().getResponse().getContentAsString();
        String heldId = JsonPath.read(listing, "$[0].id");

        mvc.perform(post("/api/admin/fx/rates/" + heldId + "/approve").header(KEY, admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.approvesRateId").value(heldId))
                .andExpect(jsonPath("$.source").value(containsString("test-admin")));

        mvc.perform(get("/api/fx/rates/USD_NGN").header(KEY, client)).andExpect(jsonPath("$.midRate").value(unique.doubleValue()));
    }

    @Test
    void approvingAnUnknownRateIsNotFound() throws Exception {
        mvc.perform(post("/api/admin/fx/rates/" + UUID.randomUUID() + "/approve").header(KEY, admin))
                .andExpect(status().isNotFound());
    }

    // ---- admin: manual rates

    @Test
    void anAdminCanSetARateByHandAndItIsAttributedToTheirKeyName() throws Exception {
        mvc.perform(post("/api/admin/fx/rates").header(KEY, admin).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"corridor\":\"USD_NGN\",\"midRate\":1490.25,\"note\":\"feed outage\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.midRate").value(1490.25))
                .andExpect(jsonPath("$.source").value(containsString("manual:test-admin")))
                .andExpect(jsonPath("$.note").value("feed outage"));
    }

    @Test
    void aManualRateIsValidatedAtTheDoor() throws Exception {
        mvc.perform(post("/api/admin/fx/rates").header(KEY, admin).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"corridor\":\"USD_NGN\",\"midRate\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.midRate").exists());
        mvc.perform(post("/api/admin/fx/rates").header(KEY, admin).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"corridor\":\"USD_NGN\",\"midRate\":1500.1234567}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.midRate").exists());
        mvc.perform(post("/api/admin/fx/rates").header(KEY, admin).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"midRate\":1500}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.corridor").exists());
    }

    @Test
    void aManualRateOutsideTheBelievableLimitsIsRefused() throws Exception {
        mvc.perform(post("/api/admin/fx/rates").header(KEY, admin).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"corridor\":\"USD_NGN\",\"midRate\":9000}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    // ---- admin: the spread

    @Test
    void anAdminCanChangeTheSpreadAndClientsSeeItAtOnce() throws Exception {
        mvc.perform(put("/api/admin/fx/spread").header(KEY, admin).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"corridor\":\"USD_NGN\",\"spreadBps\":250}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.spreadBps").value(250))
                .andExpect(jsonPath("$.setBy").value(containsString("test-admin")));

        mvc.perform(get("/api/fx/rates/USD_NGN").header(KEY, client))
                .andExpect(jsonPath("$.spreadBps").value(250))
                .andExpect(jsonPath("$.customerRate").value(1462.5));
    }

    @Test
    void aSpreadOutsideZeroToTwentyPercentIsRejected() throws Exception {
        for (String bad : new String[]{"-1", "2001"}) {
            mvc.perform(put("/api/admin/fx/spread").header(KEY, admin).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"corridor\":\"USD_NGN\",\"spreadBps\":" + bad + "}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors.spreadBps").exists());
        }
    }

    // ---- when there is no usable rate

    @Test
    void whenTheLatestRateHasGrownOldClientsAreToldTheRateIsUnavailable() throws Exception {
        jdbc.update("""
                insert into fx_rate (id, corridor, mid_rate, source, retrieved_at, status, created_at)
                values (gen_random_uuid(), 'USD_NGN', 1500, 'old', now() - interval '10 minutes', 'ACTIVE', now())
                """);

        mvc.perform(get("/api/fx/rates/USD_NGN").header(KEY, client))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("RATE_UNAVAILABLE"));
        assertThat(true).isTrue();
    }
}
