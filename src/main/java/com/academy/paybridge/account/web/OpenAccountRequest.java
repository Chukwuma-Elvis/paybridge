package com.academy.paybridge.account.web;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record OpenAccountRequest(
        @NotNull(message = "must not be null")
        UUID customerId,

        /** Optional. Defaults to NGN. */
        String currency) {

    String currencyOrDefault() {
        return currency == null || currency.isBlank() ? "NGN" : currency.trim().toUpperCase();
    }
}
