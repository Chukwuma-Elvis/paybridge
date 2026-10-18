package com.academy.paybridge.fx.service;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.math.BigDecimal;
import java.time.Duration;

/**
 * Settings for the {@code fx} module, from {@code paybridge.fx} in application.yml. The figures are placeholders.
 *
 * @param usdAccountsEnabled   whether customers may open USD accounts (read by the account module)
 * @param maxRateAge           a rate older than this is refused for new quotes
 * @param spikeThresholdPercent a new rate that differs from the previous one by MORE than this percentage is held for an admin
 * @param defaultSpreadBps     our margin in basis points (100 is 1%) until an admin sets one
 * @param quoteTtl             how long a quote stays valid
 * @param minRate              the smallest rate that is believable (anything below is rejected as a bad feed)
 * @param maxRate              the largest rate that is believable
 * @param refresher            the timer that fetches rates
 * @param quotes               limits and fees for quotes
 */
@ConfigurationProperties(prefix = "paybridge.fx")
public record FxProperties(
        @DefaultValue("false") boolean usdAccountsEnabled,
        @DefaultValue("5m") Duration maxRateAge,
        @DefaultValue("5") BigDecimal spikeThresholdPercent,
        @DefaultValue("100") int defaultSpreadBps,
        @DefaultValue("5m") Duration quoteTtl,
        @DefaultValue("100") BigDecimal minRate,
        @DefaultValue("5000") BigDecimal maxRate,
        @DefaultValue Refresher refresher,
        @DefaultValue Quotes quotes) {

    /**
     * @param enabled  whether the timer runs
     * @param interval how often it asks the rate source
     */
    public record Refresher(@DefaultValue("true") boolean enabled, @DefaultValue("60s") Duration interval) {
    }

    /**
     * @param feeBps       our fee in basis points of the amount sold (50 is 0.5%), rounded up
     * @param minFeeMinor  the smallest fee, in minor units of the sold currency (100 is USD 1.00)
     * @param minSellMinor the smallest amount that can be quoted (500 is USD 5.00)
     * @param maxSellMinor the largest amount that can be quoted (1,000,000 is USD 10,000.00)
     */
    public record Quotes(
            @DefaultValue("50") int feeBps,
            @DefaultValue("100") long minFeeMinor,
            @DefaultValue("500") long minSellMinor,
            @DefaultValue("1000000") long maxSellMinor) {
    }
}
