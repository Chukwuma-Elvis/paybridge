package com.academy.paybridge.transfer.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record InternalTransferRequest(
        @NotNull(message = "must not be null")
        UUID sourceAccountId,

        @NotBlank(message = "must not be blank")
        @Pattern(regexp = "\\d{10}", message = "must be a 10-digit account number")
        String destinationAccountNumber,

        @NotNull(message = "must not be null")
        @Positive(message = "must be greater than zero")
        Long amountMinor,

        @Size(max = 140, message = "must be at most 140 characters")
        String narration) {
}
