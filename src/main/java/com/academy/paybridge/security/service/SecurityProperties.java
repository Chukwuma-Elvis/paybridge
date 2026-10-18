package com.academy.paybridge.security.service;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings for API-key security, from {@code paybridge.security} in application.yml.
 *
 * @param enabled           when false every request is let through unchecked. For a private developer machine only
 * @param bootstrapAdminKey an admin key to register at startup (give it through the PAYBRIDGE_ADMIN_API_KEY environment
 *                          variable, never in a file). It is how the very first admin key comes to exist
 * @param rateLimit         how many requests each caller may make
 */
@ConfigurationProperties(prefix = "paybridge.security")
public record SecurityProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("") String bootstrapAdminKey,
        @DefaultValue RateLimit rateLimit) {

    /**
     * @param requestsPerMinute  per API key, across every endpoint
     * @param transfersPerMinute per API key, for submitting transfers only (a tighter limit on the endpoints that move money)
     * @param failedAuthPerMinute per client address, for requests that present a key which does not exist
     * @param webhooksPerMinute   per client address, for payment-provider webhooks (which carry no API key)
     */
    public record RateLimit(
            @DefaultValue("120") int requestsPerMinute,
            @DefaultValue("30") int transfersPerMinute,
            @DefaultValue("10") int failedAuthPerMinute,
            @DefaultValue("600") int webhooksPerMinute) {
    }
}
