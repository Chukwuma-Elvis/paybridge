package com.academy.paybridge.transfer.web;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record ExternalTransferRequest(
        @NotNull(message = "must not be null")
        UUID sourceAccountId,

        @NotNull(message = "must not be null")
        UUID beneficiaryId,

        @NotNull(message = "must not be null")
        @Positive(message = "must be greater than zero")
        Long amountMinor,

        @Size(max = 140, message = "must be at most 140 characters")
        String narration) {
}
