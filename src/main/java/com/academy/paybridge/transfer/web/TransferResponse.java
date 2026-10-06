package com.academy.paybridge.transfer.web;

import com.academy.paybridge.transfer.api.TransferStatus;
import com.academy.paybridge.transfer.api.TransferSummary;
import com.academy.paybridge.transfer.api.TransferType;

import java.time.Instant;
import java.util.UUID;

public record TransferResponse(
        UUID id,
        String reference,
        TransferType type,
        TransferStatus status,
        UUID sourceAccountId,
        UUID destinationAccountId,
        UUID beneficiaryId,
        long amountMinor,
        long feeMinor,
        String currency,
        String narration,
        String failureReason,
        Instant createdAt,
        Instant completedAt) {

    static TransferResponse from(TransferSummary summary) {
        return new TransferResponse(
                summary.id(),
                summary.reference(),
                summary.type(),
                summary.status(),
                summary.sourceAccountId(),
                summary.destinationAccountId(),
                summary.beneficiaryId(),
                summary.amountMinor(),
                summary.feeMinor(),
                summary.currency(),
                summary.narration(),
                summary.failureReason(),
                summary.createdAt(),
                summary.completedAt());
    }
}
