package com.academy.paybridge.compliance.api;

import com.academy.paybridge.shared.money.Money;

import java.time.LocalDate;
import java.util.UUID;

/**
 * The front desk of the compliance module: the rules about how much a customer may send.
 * The limits depend on the customer's KYC tier.
 */
public interface ComplianceApi {

    /**
     * Checks the amount against the customer's per-transaction and daily limits and, if it passes, counts it
     * towards today's total in one atomic step. Call it inside the transfer's transaction: if the transfer later
     * fails and rolls back, the reservation rolls back with it.
     *
     * @throws com.academy.paybridge.shared.exception.PayBridgeException {@code LIMIT_EXCEEDED} if a limit would be broken
     */
    void reserve(UUID customerId, Money amount);

    /** Gives back a reservation, for example when an external transfer later fails. */
    void release(UUID customerId, Money amount, LocalDate usageDate);

    /** The Africa/Lagos calendar day that "today's" limit is counted against. */
    LocalDate today();

    LimitStatus limitStatus(UUID customerId);
}
