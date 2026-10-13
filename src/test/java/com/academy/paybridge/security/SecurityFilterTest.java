package com.academy.paybridge.security;

import com.academy.paybridge.security.domain.ApiKeyRole;
import com.academy.paybridge.security.service.RateLimiter;
import com.academy.paybridge.shared.config.AccessLogFilter;
import io.micrometer.core.instrument.Counter;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SecurityFilterTest extends AbstractSecurityTest {

    private static final String KEY_HEADER = "X-API-Key";

    private static String customerJson() {
        return """
                {"firstName": "Test", "lastName": "User", "email": "user-%s@example.com", "phone": "08012345678"}
                """.formatted(UUID.randomUUID());
    }

    private static RequestPostProcessor fromAddress(String address) {
        return request -> {
            request.setRemoteAddr(address);
            return request;
        };
    }

    private double count(String name, String tagKey, String tagValue) {
        Counter counter = meters.find(name).tag(tagKey, tagValue).counter();
        return counter == null ? 0 : counter.count();
    }

    // ---- who may come in -------------------------------------------------------------------------------------

    @Test
    void aRequestWithNoKeyIsTurnedAwayInTheStandardErrorShape() throws Exception {
        secured().perform(get("/api/customers/" + UUID.randomUUID()))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", containsString("ApiKey")))
                .andExpect(header().exists("X-Request-Id"))
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.detail").value(containsString("X-API-Key")))
                .andExpect(jsonPath("$.requestId").value(notNullValue()));
    }

    @Test
    void aBlankKeyCountsAsNoKey() throws Exception {
        secured().perform(get("/api/customers/" + UUID.randomUUID()).header(KEY_HEADER, "   "))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void anUnknownKeyIsTurnedAway() throws Exception {
        secured().perform(get("/api/customers/" + UUID.randomUUID()).header(KEY_HEADER, "pb_" + "z".repeat(43)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.detail").value("The API key is not valid."));
    }

    @Test
    void aMalformedKeyIsTurnedAway() throws Exception {
        secured().perform(get("/api/customers/" + UUID.randomUUID()).header(KEY_HEADER, "not a key"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void theErrorNeverRepeatsTheKeyThatWasSent() throws Exception {
        String sent = "pb_" + "s".repeat(43);

        String body = secured().perform(get("/api/customers/" + UUID.randomUUID()).header(KEY_HEADER, sent))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain(sent);
    }

    @Test
    void aValidClientKeyReachesTheController() throws Exception {
        String key = newKey(ApiKeyRole.CLIENT);

        secured().perform(post("/api/customers").header(KEY_HEADER, key)
                        .contentType(MediaType.APPLICATION_JSON).content(customerJson()))
                .andExpect(status().isCreated());
    }

    @Test
    void aRevokedKeyIsTurnedAway() throws Exception {
        var created = apiKeyService.create("soon-revoked", ApiKeyRole.CLIENT);
        secured().perform(get("/api/customers/" + UUID.randomUUID()).header(KEY_HEADER, created.plaintext()))
                .andExpect(status().isNotFound()); // let in: the customer just does not exist

        apiKeyService.revoke(created.key().id());

        secured().perform(get("/api/customers/" + UUID.randomUUID()).header(KEY_HEADER, created.plaintext()))
                .andExpect(status().isUnauthorized());
    }

    // ---- who may go where --------------------------------------------------------------------------------------

    @Test
    void healthIsOpenToEveryone() throws Exception {
        secured().perform(get("/actuator/health")).andExpect(status().isOk());
    }

    @Test
    void aClientKeyCannotUseTheAdminEndpoints() throws Exception {
        String key = newKey(ApiKeyRole.CLIENT);

        secured().perform(get("/api/admin/api-keys").header(KEY_HEADER, key))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"))
                .andExpect(jsonPath("$.detail").value("This endpoint needs an admin API key."));
    }

    @Test
    void aClientKeyCannotMintMoneyThroughTheDevFundingEndpoint() throws Exception {
        String key = newKey(ApiKeyRole.CLIENT);

        secured().perform(post("/api/dev/accounts/" + UUID.randomUUID() + "/fund").header(KEY_HEADER, key)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"amountMinor\": 100}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void anAdminKeyCanUseTheDevFundingEndpoint() throws Exception {
        String admin = newKey(ApiKeyRole.ADMIN);
        UUID accountId = newAccount();

        secured().perform(post("/api/dev/accounts/" + accountId + "/fund").header(KEY_HEADER, admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"amountMinor\": 5000}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balanceMinor").value(5000));
    }

    @Test
    void metricsNeedAnAdminKey() throws Exception {
        MockMvc mvc = secured();

        mvc.perform(get("/actuator/metrics")).andExpect(status().isUnauthorized());
        mvc.perform(get("/actuator/metrics").header(KEY_HEADER, newKey(ApiKeyRole.CLIENT))).andExpect(status().isForbidden());
        mvc.perform(get("/actuator/metrics").header(KEY_HEADER, newKey(ApiKeyRole.ADMIN))).andExpect(status().isOk());
    }

    @Test
    void disguisedAddressesDoNotLetAClientIntoAdminAreas() throws Exception {
        MockMvc mvc = secured();
        String client = newKey(ApiKeyRole.CLIENT);

        mvc.perform(get("/actuator/health/../metrics").header(KEY_HEADER, client)).andExpect(status().isForbidden());
        mvc.perform(get("/api/customers/../admin/api-keys").header(KEY_HEADER, client)).andExpect(status().isForbidden());
        mvc.perform(get("/API/ADMIN/API-KEYS").header(KEY_HEADER, client)).andExpect(status().isForbidden());
        mvc.perform(get("/api//admin/api-keys").header(KEY_HEADER, client)).andExpect(status().isForbidden());
    }

    @Test
    void everyOtherAddressNeedsAKeyEvenOnesThatDoNotExist() throws Exception {
        secured().perform(get("/no/such/place")).andExpect(status().isUnauthorized());
    }

    @Test
    void withSecurityTurnedOffEverythingIsLetThrough() throws Exception {
        secured(properties(false), new RateLimiter(10, clock), new RateLimiter(10, clock), new RateLimiter(10, clock))
                .perform(get("/api/customers/" + UUID.randomUUID()))
                .andExpect(status().isNotFound()); // reached the controller, which says the customer does not exist
    }

    // ---- guessing keys -----------------------------------------------------------------------------------------

    @Test
    void anAddressThatKeepsGuessingWrongKeysIsBlockedEvenForARightKey() throws Exception {
        MockMvc mvc = secured(ENABLED, new RateLimiter(3, clock), new RateLimiter(1_000, clock), new RateLimiter(1_000, clock));
        String goodKey = newKey(ApiKeyRole.CLIENT);
        String attacker = "203.0.113." + (int) (Math.random() * 200 + 1);

        for (int guess = 0; guess < 3; guess++) {
            mvc.perform(get("/api/customers/" + UUID.randomUUID()).header(KEY_HEADER, "pb_wrong" + "q".repeat(35) + guess)
                            .with(fromAddress(attacker)))
                    .andExpect(status().isUnauthorized());
        }

        mvc.perform(get("/api/customers/" + UUID.randomUUID()).header(KEY_HEADER, goodKey).with(fromAddress(attacker)))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"));
    }

    @Test
    void oneAddressBeingBlockedDoesNotAffectAnother() throws Exception {
        MockMvc mvc = secured(ENABLED, new RateLimiter(2, clock), new RateLimiter(1_000, clock), new RateLimiter(1_000, clock));
        String goodKey = newKey(ApiKeyRole.CLIENT);

        for (int guess = 0; guess < 2; guess++) {
            mvc.perform(get("/api/customers/" + UUID.randomUUID()).header(KEY_HEADER, "pb_wrong" + "q".repeat(35) + guess)
                    .with(fromAddress("198.51.100.1")));
        }

        mvc.perform(get("/api/customers/" + UUID.randomUUID()).header(KEY_HEADER, goodKey).with(fromAddress("198.51.100.2")))
                .andExpect(status().isNotFound()); // let in
    }

    @Test
    void forgettingTheKeyDoesNotCountAsGuessing() throws Exception {
        MockMvc mvc = secured(ENABLED, new RateLimiter(2, clock), new RateLimiter(1_000, clock), new RateLimiter(1_000, clock));

        for (int attempt = 0; attempt < 5; attempt++) {
            mvc.perform(get("/api/customers/" + UUID.randomUUID()).with(fromAddress("198.51.100.9")))
                    .andExpect(status().isUnauthorized());
        }
    }

    // ---- going too fast ----------------------------------------------------------------------------------------

    @Test
    void aKeyThatCallsTooOftenIsToldToSlowDown() throws Exception {
        MockMvc mvc = secured(ENABLED, new RateLimiter(1_000, clock), new RateLimiter(3, clock), new RateLimiter(1_000, clock));
        String key = newKey(ApiKeyRole.CLIENT);

        for (int call = 0; call < 3; call++) {
            mvc.perform(get("/api/customers/" + UUID.randomUUID()).header(KEY_HEADER, key)).andExpect(status().isNotFound());
        }

        mvc.perform(get("/api/customers/" + UUID.randomUUID()).header(KEY_HEADER, key))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"))
                .andExpect(jsonPath("$.detail").value(containsString("second")));
    }

    @Test
    void oneKeysSpeedDoesNotSlowAnotherKey() throws Exception {
        MockMvc mvc = secured(ENABLED, new RateLimiter(1_000, clock), new RateLimiter(2, clock), new RateLimiter(1_000, clock));
        String busy = newKey(ApiKeyRole.CLIENT);
        String quiet = newKey(ApiKeyRole.CLIENT);

        for (int call = 0; call < 3; call++) {
            mvc.perform(get("/api/customers/" + UUID.randomUUID()).header(KEY_HEADER, busy));
        }

        mvc.perform(get("/api/customers/" + UUID.randomUUID()).header(KEY_HEADER, busy)).andExpect(status().isTooManyRequests());
        mvc.perform(get("/api/customers/" + UUID.randomUUID()).header(KEY_HEADER, quiet)).andExpect(status().isNotFound());
    }

    @Test
    void submittingTransfersHasATighterLimitThanReading() throws Exception {
        MockMvc mvc = secured(ENABLED, new RateLimiter(1_000, clock), new RateLimiter(1_000, clock), new RateLimiter(2, clock));
        String key = newKey(ApiKeyRole.CLIENT);
        String body = """
                {"sourceAccountId": "%s", "destinationAccountNumber": "1000000017", "amountMinor": 100}
                """.formatted(UUID.randomUUID());

        for (int submission = 0; submission < 2; submission++) {
            mvc.perform(post("/api/transfers/internal").header(KEY_HEADER, key).header("Idempotency-Key", "limit-test-" + submission + "-" + UUID.randomUUID())
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isNotFound()); // past the limiter; the source account does not exist
        }

        mvc.perform(post("/api/transfers/internal").header(KEY_HEADER, key).header("Idempotency-Key", "limit-test-3-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isTooManyRequests());

        mvc.perform(get("/api/customers/" + UUID.randomUUID()).header(KEY_HEADER, key))
                .andExpect(status().isNotFound()); // reading is still allowed
    }

    @Test
    void healthChecksAreNeverRateLimited() throws Exception {
        MockMvc mvc = secured(ENABLED, new RateLimiter(1_000, clock), new RateLimiter(1, clock), new RateLimiter(1, clock));

        for (int probe = 0; probe < 20; probe++) {
            mvc.perform(get("/actuator/health")).andExpect(status().isOk());
        }
    }

    // ---- the paper trail ---------------------------------------------------------------------------------------

    @Test
    void theAccessLogCanNameTheCaller() throws Exception {
        var created = apiKeyService.create("mobile-app", ApiKeyRole.CLIENT);

        var request = secured().perform(get("/api/customers/" + UUID.randomUUID()).header(KEY_HEADER, created.plaintext()))
                .andReturn().getRequest();

        assertThat(request.getAttribute(AccessLogFilter.CLIENT_ATTRIBUTE)).isEqualTo("mobile-app");
        assertThat(String.valueOf(request.getAttribute(AccessLogFilter.CLIENT_ATTRIBUTE))).doesNotContain(created.plaintext());
    }

    @Test
    void rejectionsAreCounted() throws Exception {
        double missingBefore = count("paybridge.security.rejections", "reason", "missing_key");
        double invalidBefore = count("paybridge.security.rejections", "reason", "invalid_key");
        double forbiddenBefore = count("paybridge.security.rejections", "reason", "forbidden");
        MockMvc mvc = secured();

        mvc.perform(get("/api/customers/" + UUID.randomUUID()));
        mvc.perform(get("/api/customers/" + UUID.randomUUID()).header(KEY_HEADER, "pb_" + "n".repeat(43)));
        mvc.perform(get("/api/admin/api-keys").header(KEY_HEADER, newKey(ApiKeyRole.CLIENT)));

        assertThat(count("paybridge.security.rejections", "reason", "missing_key")).isEqualTo(missingBefore + 1);
        assertThat(count("paybridge.security.rejections", "reason", "invalid_key")).isEqualTo(invalidBefore + 1);
        assertThat(count("paybridge.security.rejections", "reason", "forbidden")).isEqualTo(forbiddenBefore + 1);
    }

    @Test
    void tooManyRequestsAreCountedByWhichLimitWasHit() throws Exception {
        double before = count("paybridge.ratelimit.rejections", "limiter", "api");
        MockMvc mvc = secured(ENABLED, new RateLimiter(1_000, clock), new RateLimiter(1, clock), new RateLimiter(1_000, clock));
        String key = newKey(ApiKeyRole.CLIENT);

        mvc.perform(get("/api/customers/" + UUID.randomUUID()).header(KEY_HEADER, key));
        mvc.perform(get("/api/customers/" + UUID.randomUUID()).header(KEY_HEADER, key));

        assertThat(count("paybridge.ratelimit.rejections", "limiter", "api")).isEqualTo(before + 1);
    }
}
