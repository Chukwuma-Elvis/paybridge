package com.academy.paybridge.security.web;

import com.academy.paybridge.shared.config.RequestIdFilter;
import com.academy.paybridge.shared.exception.ErrorCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

class ProblemJsonTest {

    private final JsonMapper json = JsonMapper.builder().build();

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void writesTheSameShapeAsEveryOtherPayBridgeError() throws Exception {
        MDC.put(RequestIdFilter.MDC_KEY, "req-123");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/customers");
        MockHttpServletResponse response = new MockHttpServletResponse();

        ProblemJson.write(request, response, ErrorCode.UNAUTHORIZED, "An API key is required.");

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentType()).startsWith("application/problem+json");
        JsonNode body = json.readTree(response.getContentAsString());
        assertThat(body.get("detail").asString()).isEqualTo("An API key is required.");
        assertThat(body.get("instance").asString()).isEqualTo("/api/customers");
        assertThat(body.get("status").asInt()).isEqualTo(401);
        assertThat(body.get("title").asString()).isEqualTo("Unauthorized");
        assertThat(body.get("code").asString()).isEqualTo("UNAUTHORIZED");
        assertThat(body.get("requestId").asString()).isEqualTo("req-123");
    }

    @Test
    void leavesOutTheRequestIdWhenThereIsNone() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        ProblemJson.write(new MockHttpServletRequest("GET", "/x"), response, ErrorCode.FORBIDDEN, "No.");

        assertThat(json.readTree(response.getContentAsString()).has("requestId")).isFalse();
    }

    @Test
    void anAwkwardAddressCannotBreakOutOfTheJson() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/a\"b\\c\nd\u0001e");
        MockHttpServletResponse response = new MockHttpServletResponse();

        ProblemJson.write(request, response, ErrorCode.RATE_LIMITED, "Slow \"down\".");

        JsonNode body = json.readTree(response.getContentAsString());
        assertThat(body.get("instance").asString()).isEqualTo("/a\"b\\c\nd\u0001e");
        assertThat(body.get("detail").asString()).isEqualTo("Slow \"down\".");
        assertThat(response.getStatus()).isEqualTo(429);
    }

    @Test
    void quoteEscapesTheCharactersThatMatter() {
        assertThat(ProblemJson.quote("plain")).isEqualTo("\"plain\"");
        assertThat(ProblemJson.quote("a\"b")).isEqualTo("\"a\\\"b\"");
        assertThat(ProblemJson.quote("a\\b")).isEqualTo("\"a\\\\b\"");
        assertThat(ProblemJson.quote("a\nb\tc\rd")).isEqualTo("\"a\\nb\\tc\\rd\"");
        assertThat(ProblemJson.quote("\u0007")).isEqualTo("\"\\u0007\"");
    }
}
