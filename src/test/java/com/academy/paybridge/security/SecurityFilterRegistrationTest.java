package com.academy.paybridge.security;

import com.academy.paybridge.AbstractIntegrationTest;
import com.academy.paybridge.security.web.ApiKeyAuthFilter;
import com.academy.paybridge.security.web.CorsFilter;
import com.academy.paybridge.security.web.RateLimitFilter;
import com.academy.paybridge.shared.config.AccessLogFilter;
import com.academy.paybridge.shared.config.RequestIdFilter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.core.Ordered;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Checks that the RUNNING application installs all security and tracing filters in the exact required order:
 * RequestId (+0) -> AccessLog (+1) -> CORS (+2) -> ApiKeyAuth (+3) -> RateLimit (+4).
 * (CORS must run before auth so that preflight OPTIONS requests are answered without demanding an API key).
 */
class SecurityFilterRegistrationTest extends AbstractIntegrationTest {

    @Autowired
    private FilterRegistrationBean<RequestIdFilter> requestIdRegistration;

    @Autowired
    private FilterRegistrationBean<AccessLogFilter> accessLogRegistration;

    @Autowired
    private FilterRegistrationBean<CorsFilter> corsRegistration;

    @Autowired
    private FilterRegistrationBean<ApiKeyAuthFilter> authRegistration;

    @Autowired
    private FilterRegistrationBean<RateLimitFilter> rateLimitRegistration;

    @Test
    void theFiveFiltersRunInTheRightOrder() {
        assertThat(requestIdRegistration.getOrder()).isEqualTo(Ordered.HIGHEST_PRECEDENCE);
        assertThat(accessLogRegistration.getOrder()).isEqualTo(Ordered.HIGHEST_PRECEDENCE + 1);
        assertThat(corsRegistration.getOrder()).isEqualTo(Ordered.HIGHEST_PRECEDENCE + 2);
        assertThat(authRegistration.getOrder()).isEqualTo(Ordered.HIGHEST_PRECEDENCE + 3);
        assertThat(rateLimitRegistration.getOrder()).isEqualTo(Ordered.HIGHEST_PRECEDENCE + 4);
    }

    @Test
    void theFiltersAreTheRealClassesNotProxies() {
        assertThat(corsRegistration.getFilter()).isExactlyInstanceOf(CorsFilter.class);
        assertThat(authRegistration.getFilter()).isExactlyInstanceOf(ApiKeyAuthFilter.class);
        assertThat(rateLimitRegistration.getFilter()).isExactlyInstanceOf(RateLimitFilter.class);
    }
}
