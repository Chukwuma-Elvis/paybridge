package com.academy.paybridge.transfer.service;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Settings for external transfers, from {@code paybridge.transfer} in application.yml. Amounts are in kobo.
 *
 * @param externalMinMinor the smallest external transfer we accept
 * @param externalFees     the fee for an external transfer, by amount band
 */
@ConfigurationProperties(prefix = "paybridge.transfer")
public record TransferProperties(long externalMinMinor, List<FeeBand> externalFees) {

    /**
     * @param upToMinor the largest amount (inclusive) this band covers; absent means "no upper limit"
     * @param feeMinor  the fee charged for amounts in this band
     */
    public record FeeBand(Long upToMinor, long feeMinor) {
    }
}
