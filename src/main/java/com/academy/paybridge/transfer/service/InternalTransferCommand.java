package com.academy.paybridge.transfer.service;

import java.util.UUID;

public record InternalTransferCommand(
        UUID sourceAccountId,
        String destinationAccountNumber,
        long amountMinor,
        String narration) {
}
