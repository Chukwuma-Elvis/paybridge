package com.academy.paybridge.security.web;

import com.academy.paybridge.security.service.ApiKeyService;
import com.academy.paybridge.security.service.RateLimiter;
import com.academy.paybridge.security.service.SecurityProperties;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

import java.time.Clock;

/**
 * Puts the two security filters into the web server's chain, in order. They are registered here, not declared as beans,
 * because Spring Modulith wraps module beans in proxies that break servlet filters (see {@code RequestIdFilterConfig}).
 * <p>
 * The order, outermost first: request id, access log, API-key check, rate limit, then Spring MVC and the controllers.
 */
@Configuration
class SecurityFilterConfig {

    @Bean
    FilterRegistrationBean<ApiKeyAuthFilter> apiKeyAuthFilterRegistration(ApiKeyService apiKeyService,
                                                                          SecurityProperties properties,
                                                                          Clock clock,
                                                                          MeterRegistry meters) {
        RateLimiter failedAuthLimiter = new RateLimiter(properties.rateLimit().failedAuthPerMinute(), clock);
        FilterRegistrationBean<ApiKeyAuthFilter> registration =
                new FilterRegistrationBean<>(new ApiKeyAuthFilter(apiKeyService, properties, failedAuthLimiter, meters));
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 2);
        return registration;
    }

    @Bean
    FilterRegistrationBean<RateLimitFilter> rateLimitFilterRegistration(SecurityProperties properties,
                                                                        Clock clock,
                                                                        MeterRegistry meters) {
        RateLimiter apiLimiter = new RateLimiter(properties.rateLimit().requestsPerMinute(), clock);
        RateLimiter transferLimiter = new RateLimiter(properties.rateLimit().transfersPerMinute(), clock);
        FilterRegistrationBean<RateLimitFilter> registration =
                new FilterRegistrationBean<>(new RateLimitFilter(apiLimiter, transferLimiter, meters));
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 3);
        return registration;
    }
}
