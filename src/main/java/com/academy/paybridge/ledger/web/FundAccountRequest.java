package com.academy.paybridge.ledger.web;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record FundAccountRequest(
        @NotNull(message = "must not be null")
        @Positive(message = "must be greater than zero")
        Long amountMinor) {
}
