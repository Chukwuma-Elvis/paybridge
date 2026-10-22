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
 * Puts the security filters into the web server's chain, in order. They are registered here, not declared as beans,
 * because Spring Modulith wraps module beans in proxies that break servlet filters (see {@code RequestIdFilterConfig}).
 * <p>
 * The order, outermost first: request id (+0), access log (+1), CORS (+2), API-key check (+3), rate limit (+4),
 * then Spring MVC and the controllers.
 */
@Configuration
class SecurityFilterConfig {

    @Bean
    FilterRegistrationBean<CorsFilter> corsFilterRegistration(SecurityProperties properties) {
        FilterRegistrationBean<CorsFilter> registration =
                new FilterRegistrationBean<>(new CorsFilter(properties.cors()));
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 2);
        return registration;
    }

    @Bean
    FilterRegistrationBean<ApiKeyAuthFilter> apiKeyAuthFilterRegistration(ApiKeyService apiKeyService,
                                                                          SecurityProperties properties,
                                                                          Clock clock,
                                                                          MeterRegistry meters) {
        RateLimiter failedAuthLimiter = new RateLimiter(properties.rateLimit().failedAuthPerMinute(), clock);
        FilterRegistrationBean<ApiKeyAuthFilter> registration =
                new FilterRegistrationBean<>(new ApiKeyAuthFilter(apiKeyService, properties, failedAuthLimiter, meters));
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 3);
        return registration;
    }

    @Bean
    FilterRegistrationBean<RateLimitFilter> rateLimitFilterRegistration(SecurityProperties properties,
                                                                        Clock clock,
                                                                        MeterRegistry meters) {
        RateLimiter apiLimiter = new RateLimiter(properties.rateLimit().requestsPerMinute(), clock);
        RateLimiter transferLimiter = new RateLimiter(properties.rateLimit().transfersPerMinute(), clock);
        RateLimiter webhookLimiter = new RateLimiter(properties.rateLimit().webhooksPerMinute(), clock);
        FilterRegistrationBean<RateLimitFilter> registration =
                new FilterRegistrationBean<>(new RateLimitFilter(apiLimiter, transferLimiter, webhookLimiter, meters));
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 4);
        return registration;
    }
}
