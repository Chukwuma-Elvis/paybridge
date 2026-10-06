package com.academy.paybridge.transfer.service;

import com.academy.paybridge.transfer.api.TransferSummary;
import com.academy.paybridge.transfer.domain.Transfer;

final class TransferMapper {

    private TransferMapper() {
    }

    static TransferSummary toSummary(Transfer transfer) {
        return new TransferSummary(
                transfer.getId(),
                transfer.getReference(),
                transfer.getType(),
                transfer.getStatus(),
                transfer.getCustomerId(),
                transfer.getSourceAccountId(),
                transfer.getDestinationAccountId(),
                transfer.getBeneficiaryId(),
                transfer.getAmountMinor(),
                transfer.getFeeMinor(),
                transfer.getCurrency(),
                transfer.getNarration(),
                transfer.getFailureReason(),
                transfer.getCreatedAt(),
                transfer.getCompletedAt());
    }
}
