package com.academy.paybridge.customer.web;

import com.academy.paybridge.customer.api.CustomerStatus;
import com.academy.paybridge.customer.api.CustomerSummary;
import com.academy.paybridge.customer.api.KycTier;

import java.util.UUID;

public record CustomerResponse(
        UUID id,
        String firstName,
        String lastName,
        String email,
        String phone,
        KycTier kycTier,
        CustomerStatus status) {

    static CustomerResponse from(CustomerSummary summary) {
        return new CustomerResponse(
                summary.id(),
                summary.firstName(),
                summary.lastName(),
                summary.email(),
                summary.phone(),
                summary.kycTier(),
                summary.status());
    }
}
