package com.academy.paybridge.compliance.service;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * The anti-money-laundering settings, from {@code paybridge.aml}. EVERY figure is a placeholder: real thresholds come from the
 * compliance officer and the regulator, not from this file.
 */
@ConfigurationProperties(prefix = "paybridge.aml")
public record AmlProperties(
        @DefaultValue Screening screening,
        @DefaultValue UsdLimits usdLimits,
        @DefaultValue Velocity velocity) {

    /**
     * @param reviewThreshold    name similarity (0 to 1) at or above which a hit needs a person, even with nothing to back it up
     * @param blockThreshold     similarity at or above which a hit with at least one corroborator is blocked
     * @param corroboratedReview a lower similarity that still needs review when BOTH country and date of birth also match
     */
    public record Screening(@DefaultValue("0.85") double reviewThreshold,
                            @DefaultValue("0.95") double blockThreshold,
                            @DefaultValue("0.70") double corroboratedReview) {
    }

    public record UsdLimits(@DefaultValue Limit sender, @DefaultValue Limit recipient) {
    }

    /** All in US cents. */
    public record Limit(@DefaultValue("500000") long perTransactionMinor,
                        @DefaultValue("1000000") long dailyMinor,
                        @DefaultValue("5000000") long monthlyMinor) {
    }

    /**
     * @param window            the look-back for "too many"
     * @param maxCount          this many attempts inside the window is too many
     * @param thresholdMinor    the amount people might try to stay under
     * @param bandPercent       "just under" means at least this percent of the threshold, but below it
     * @param structuringCount  this many just-under attempts inside the structuring window raises a flag
     * @param structuringWindow the look-back for structuring
     */
    public record Velocity(@DefaultValue("24h") Duration window,
                           @DefaultValue("5") int maxCount,
                           @DefaultValue("300000") long thresholdMinor,
                           @DefaultValue("90") int bandPercent,
                           @DefaultValue("3") int structuringCount,
                           @DefaultValue("72h") Duration structuringWindow) {
    }
}
