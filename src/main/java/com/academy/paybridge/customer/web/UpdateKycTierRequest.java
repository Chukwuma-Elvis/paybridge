package com.academy.paybridge.customer.web;

import com.academy.paybridge.customer.api.KycTier;
import jakarta.validation.constraints.NotNull;

public record UpdateKycTierRequest(
        @NotNull(message = "must be one of UNVERIFIED, BASIC, FULL")
        KycTier kycTier) {
}
