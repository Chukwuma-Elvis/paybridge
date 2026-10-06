package com.academy.paybridge.transfer.service;

import java.util.UUID;

public record ExternalTransferCommand(
        UUID sourceAccountId,
        UUID beneficiaryId,
        long amountMinor,
        String narration) {
}
