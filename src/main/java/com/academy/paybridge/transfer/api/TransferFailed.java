package com.academy.paybridge.transfer.api;

import java.util.UUID;

/** Published when an (external) transfer did not go through and the customer has been refunded. */
public record TransferFailed(
        UUID transferId,
        String reference,
        UUID customerId,
        UUID sourceAccountId,
        long amountMinor,
        String currency,
        String reason) {
}
