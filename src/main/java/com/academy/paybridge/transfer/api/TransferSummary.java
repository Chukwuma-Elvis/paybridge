package com.academy.paybridge.transfer.api;

import java.time.Instant;
import java.util.UUID;

/** The read-only view of a transfer that other modules and the web layer use. */
public record TransferSummary(
        UUID id,
        String reference,
        TransferType type,
        TransferStatus status,
        UUID customerId,
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
}
