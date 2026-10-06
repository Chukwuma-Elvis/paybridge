package com.academy.paybridge.transfer.api;

import java.util.UUID;

/** Published when money has moved successfully. Other modules (such as notification) react to it. */
public record TransferCompleted(
        UUID transferId,
        String reference,
        TransferType type,
        UUID customerId,
        UUID sourceAccountId,
        UUID destinationAccountId,
        long amountMinor,
        long feeMinor,
        String currency) {
}
