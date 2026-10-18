package com.academy.paybridge.settlement.service;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.util.List;

/**
 * Settings for the {@code settlement} module, from {@code paybridge.settlement}. The figures are placeholders.
 *
 * @param partners  the corridor partners that are settled (the codes used in their settlement account names)
 * @param tolerance how close two figures must be to count as the same
 * @param statement limits on an uploaded statement
 * @param closer    the timer that closes batches
 */
@ConfigurationProperties(prefix = "paybridge.settlement")
public record SettlementProperties(
        @DefaultValue("fakepartner") List<String> partners,
        @DefaultValue Tolerance tolerance,
        @DefaultValue Statement statement,
        @DefaultValue Closer closer) {

    /**
     * @param amountMinor how many minor units two amounts may differ by and still be "the same" (one cent absorbs a rounding difference)
     * @param dateDays    how many days apart two dates may be and still be "the same day"
     */
    public record Tolerance(@DefaultValue("1") long amountMinor, @DefaultValue("1") int dateDays) {
    }

    /**
     * @param maxBytes the largest statement file accepted
     * @param maxRows  the most rows a statement may have
     */
    public record Statement(@DefaultValue("1048576") int maxBytes, @DefaultValue("5000") int maxRows) {
    }

    /**
     * @param enabled  whether the timer runs
     * @param interval how often it closes batches
     * @param lag      lines newer than this are left for the next batch, so a posting still being committed is not cut in half
     */
    public record Closer(@DefaultValue("true") boolean enabled, @DefaultValue("1h") Duration interval,
                         @DefaultValue("2m") Duration lag) {
    }
}
