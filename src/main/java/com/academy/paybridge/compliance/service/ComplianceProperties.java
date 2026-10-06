package com.academy.paybridge.compliance.service;

import com.academy.paybridge.customer.api.KycTier;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Map;

/**
 * The transfer limits for each KYC tier, read from {@code paybridge.compliance.limits} in application.yml.
 * All amounts are in kobo (100 kobo = 1 naira).
 */
@ConfigurationProperties(prefix = "paybridge.compliance")
public record ComplianceProperties(Map<KycTier, Limit> limits) {

    public record Limit(long perTransactionMinor, long dailyMinor) {
    }
}
