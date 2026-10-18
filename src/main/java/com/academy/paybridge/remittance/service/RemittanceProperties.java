package com.academy.paybridge.remittance.service;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Settings for the {@code remittance} module, from {@code paybridge.remittance}.
 *
 * @param partners             the corridor partners allowed to send remittances (lower case, 2 to 16 letters, digits, - or _)
 * @param fundingWindow        how long the partner has to confirm the sender's USD before the remittance is failed
 * @param screeningGateEnabled DEVELOPMENT ONLY: false skips screening and USD limits. Leave it true anywhere real.
 * @param processor            the timer that moves funded remittances forward
 * @param funding              the safety net for fundings whose confirmation never arrived
 */
@ConfigurationProperties(prefix = "paybridge.remittance")
public record RemittanceProperties(
        @DefaultValue("fakepartner") List<String> partners,
        @DefaultValue("24h") Duration fundingWindow,
        @DefaultValue("true") boolean screeningGateEnabled,
        @DefaultValue Processor processor,
        @DefaultValue Funding funding) {

    /**
     * @param enabled        whether the timer runs
     * @param interval       how often it runs
     * @param batchSize      how many remittances it handles per pass
     * @param maxAttempts    after this many failed tries at a step, a funded remittance is refunded
     * @param baseDelay      wait this long after the first failure, doubling each time (capped at five minutes)
     * @param payoutPollDelay how long to wait between checks of an outstanding payout
     */
    public record Processor(@DefaultValue("true") boolean enabled,
                            @DefaultValue("3s") Duration interval,
                            @DefaultValue("20") int batchSize,
                            @DefaultValue("5") int maxAttempts,
                            @DefaultValue("5s") Duration baseDelay,
                            @DefaultValue("5s") Duration payoutPollDelay) {
    }

    /**
     * @param minAge  only ask the partner about a remittance that has waited this long for its confirmation
     */
    public record Funding(@DefaultValue("30s") Duration minAge) {
    }

    public Set<String> partnerSet() {
        return partners.stream().map(p -> p.toLowerCase(Locale.ROOT)).collect(Collectors.toSet());
    }
}
