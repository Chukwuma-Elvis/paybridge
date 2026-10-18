package com.academy.paybridge.remittance.web;

import com.academy.paybridge.compliance.api.CaseDecision;
import com.academy.paybridge.remittance.api.CreateRemittance;
import com.academy.paybridge.remittance.api.RecipientType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.UUID;

/** The bodies the remittance endpoints accept. */
public final class RemittanceRequests {

    private RemittanceRequests() {
    }

    public record Create(
            @NotBlank(message = "is required") @Size(max = 16, message = "must be at most 16 characters") String partnerCode,
            @NotBlank(message = "is required") @Size(max = 100, message = "must be at most 100 characters") String partnerReference,
            @NotNull(message = "is required") UUID quoteId,
            @NotNull(message = "is required") @Valid Sender sender,
            @NotNull(message = "is required") @Valid Recipient recipient) {

        public CreateRemittance toCommand() {
            return new CreateRemittance(partnerCode, partnerReference, quoteId,
                    new CreateRemittance.Sender(sender.reference(), sender.fullName(), sender.country(), sender.dateOfBirth()),
                    new CreateRemittance.Recipient(recipient.type(), recipient.fullName(), recipient.country(), recipient.bankCode(),
                            recipient.accountNumber()));
        }
    }

    public record Sender(
            @NotBlank(message = "is required") @Size(max = 80, message = "must be at most 80 characters") String reference,
            @NotBlank(message = "is required") @Size(max = 200, message = "must be at most 200 characters") String fullName,
            @Pattern(regexp = "[A-Za-z]{2}", message = "must be a two-letter country code") String country,
            LocalDate dateOfBirth) {
    }

    public record Recipient(
            @NotNull(message = "must be PAYBRIDGE or BANK") RecipientType type,
            @NotBlank(message = "is required") @Size(max = 100, message = "must be at most 100 characters") String fullName,
            @Pattern(regexp = "[A-Za-z]{2}", message = "must be a two-letter country code") String country,
            @Pattern(regexp = "\\d{3,6}", message = "must be 3 to 6 digits") String bankCode,
            @NotBlank(message = "is required") @Pattern(regexp = "\\d{10}", message = "must be 10 digits") String accountNumber) {
    }

    public record Review(
            @NotNull(message = "must be APPROVE or REJECT") CaseDecision decision,
            @NotBlank(message = "a written reason is required") @Size(max = 500, message = "must be at most 500 characters") String reason) {
    }
}
