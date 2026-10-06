package com.academy.paybridge.transfer.api;

import java.util.UUID;

/** Published when a transfer that had succeeded was reversed afterwards and the customer has been refunded. */
public record TransferReversed(
        UUID transferId,
        String reference,
        UUID customerId,
        UUID sourceAccountId,
        long amountMinor,
        String currency,
        String reason) {
}
