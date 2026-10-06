package com.academy.paybridge.transfer.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.util.UUID;

public final class BeneficiaryRequests {

    private BeneficiaryRequests() {
    }

    public record Resolve(
            @NotBlank(message = "must not be blank")
            @Pattern(regexp = "\\d{3,6}", message = "must be a 3 to 6 digit bank code")
            String bankCode,

            @NotBlank(message = "must not be blank")
            @Pattern(regexp = "\\d{10}", message = "must be a 10-digit account number")
            String accountNumber) {
    }

    public record Create(
            @NotNull(message = "must not be null")
            UUID customerId,

            @NotBlank(message = "must not be blank")
            @Pattern(regexp = "\\d{3,6}", message = "must be a 3 to 6 digit bank code")
            String bankCode,

            @NotBlank(message = "must not be blank")
            @Pattern(regexp = "\\d{10}", message = "must be a 10-digit account number")
            String accountNumber) {
    }
}
