package com.academy.paybridge.shared.config;

import com.academy.paybridge.security.AbstractSecurityTest;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@TestPropertySource(properties = {"springdoc.api-docs.enabled=true", "springdoc.swagger-ui.enabled=true"})
class OpenApiDescriptionTest extends AbstractSecurityTest {

    @Test
    void theApiDescriptionIsOpenAndAdvertisesTheApiKeyHeader() throws Exception {
        secured().perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("X-API-Key")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("/api/remittances")));
    }

    @Test
    void theSwaggerPageIsReachableWithoutAKey() throws Exception {
        secured().perform(get("/swagger-ui/index.html")).andExpect(status().isOk());
    }

    @Test
    void realEndpointsStillNeedAKey() throws Exception {
        secured().perform(get("/api/customers")).andExpect(status().isUnauthorized());
    }
}
