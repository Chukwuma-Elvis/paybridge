package com.academy.paybridge.security;

import com.academy.paybridge.AbstractIntegrationTest;
import com.academy.paybridge.security.web.ApiKeyAuthFilter;
import com.academy.paybridge.security.web.RateLimitFilter;
import com.academy.paybridge.shared.config.AccessLogFilter;
import com.academy.paybridge.shared.config.RequestIdFilter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.core.Ordered;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The ordinary tests build a MockMvc with no filters, so this checks that the RUNNING application really installs the
 * security filters, and in the right order. (The order matters: you must know who is calling before you limit them.)
 */
class SecurityFilterRegistrationTest extends AbstractIntegrationTest {

    @Autowired
    private FilterRegistrationBean<RequestIdFilter> requestIdRegistration;

    @Autowired
    private FilterRegistrationBean<AccessLogFilter> accessLogRegistration;

    @Autowired
    private FilterRegistrationBean<ApiKeyAuthFilter> authRegistration;

    @Autowired
    private FilterRegistrationBean<RateLimitFilter> rateLimitRegistration;

    @Test
    void theFourFiltersRunInTheRightOrder() {
        assertThat(requestIdRegistration.getOrder()).isEqualTo(Ordered.HIGHEST_PRECEDENCE);
        assertThat(accessLogRegistration.getOrder()).isEqualTo(Ordered.HIGHEST_PRECEDENCE + 1);
        assertThat(authRegistration.getOrder()).isEqualTo(Ordered.HIGHEST_PRECEDENCE + 2);
        assertThat(rateLimitRegistration.getOrder()).isEqualTo(Ordered.HIGHEST_PRECEDENCE + 3);
    }

    @Test
    void theFiltersAreTheRealClassesNotProxies() {
        assertThat(authRegistration.getFilter()).isExactlyInstanceOf(ApiKeyAuthFilter.class);
        assertThat(rateLimitRegistration.getFilter()).isExactlyInstanceOf(RateLimitFilter.class);
    }
}
