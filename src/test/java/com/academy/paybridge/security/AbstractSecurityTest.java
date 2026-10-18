package com.academy.paybridge.security;

import com.academy.paybridge.AbstractIntegrationTest;
import com.academy.paybridge.security.domain.ApiKeyRole;
import com.academy.paybridge.security.service.ApiKeyService;
import com.academy.paybridge.security.service.RateLimiter;
import com.academy.paybridge.security.service.SecurityProperties;
import com.academy.paybridge.security.web.ApiKeyAuthFilter;
import com.academy.paybridge.security.web.RateLimitFilter;
import com.academy.paybridge.shared.config.AccessLogFilter;
import com.academy.paybridge.shared.config.RequestIdFilter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Clock;

/**
 * Base for tests that need the real security filters in front of the real application.
 * <p>
 * The ordinary {@code mockMvc} of {@link AbstractIntegrationTest} deliberately has NO filters, which is why all the older
 * tests do not need an API key. Here we build a second {@code MockMvc} with the same filters the running app has, in the
 * same order, so a test can choose its own (small) rate limits without waiting a minute.
 */
public abstract class AbstractSecurityTest extends AbstractIntegrationTest {

    protected static final SecurityProperties ENABLED = properties(true);

    @Autowired
    protected ApiKeyService apiKeyService;

    @Autowired
    protected MeterRegistry meters;

    @Autowired
    protected Clock clock;

    protected static SecurityProperties properties(boolean enabled) {
        return new SecurityProperties(enabled, "", new SecurityProperties.RateLimit(120, 30, 10, 600));
    }

    /** Filters as in the running app, with generous limits that a normal test will never reach. */
    protected MockMvc secured() {
        return secured(ENABLED, new RateLimiter(1_000, clock), new RateLimiter(1_000, clock), new RateLimiter(1_000, clock));
    }

    protected MockMvc secured(SecurityProperties properties, RateLimiter failedAuth, RateLimiter api, RateLimiter transfers) {
        return secured(properties, failedAuth, api, transfers, new RateLimiter(1_000, clock));
    }

    protected MockMvc secured(SecurityProperties properties, RateLimiter failedAuth, RateLimiter api, RateLimiter transfers,
                              RateLimiter webhooks) {
        return MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .addFilters(
                        new RequestIdFilter(),
                        new AccessLogFilter(),
                        new ApiKeyAuthFilter(apiKeyService, properties, failedAuth, meters),
                        new RateLimitFilter(api, transfers, webhooks, meters))
                .build();
    }

    protected String newKey(ApiKeyRole role) {
        return apiKeyService.create("test-" + role.name().toLowerCase() + "-" + System.nanoTime(), role).plaintext();
    }
}
