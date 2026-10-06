package com.academy.paybridge.customer.api;

import java.util.UUID;

/**
 * The read-only view of a customer that other modules may use. The {@code Customer} entity itself
 * never leaves the customer module.
 */
public record CustomerSummary(
        UUID id,
        String firstName,
        String lastName,
        String email,
        String phone,
        KycTier kycTier,
        CustomerStatus status) {

    public boolean isActive() {
        return status == CustomerStatus.ACTIVE;
    }

    public String fullName() {
        return firstName + " " + lastName;
    }
}
