package com.academy.paybridge.compliance.api;

import com.academy.paybridge.customer.api.KycTier;

import java.util.UUID;

/** A customer's limits and how much of today's allowance is used (all amounts in kobo). */
public record LimitStatus(
        UUID customerId,
        KycTier kycTier,
        long perTransactionMinor,
        long dailyMinor,
        long usedTodayMinor) {

    public long remainingTodayMinor() {
        return Math.max(0, dailyMinor - usedTodayMinor);
    }
}
