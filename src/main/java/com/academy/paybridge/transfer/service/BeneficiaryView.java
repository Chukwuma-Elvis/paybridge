package com.academy.paybridge.transfer.service;

import com.academy.paybridge.transfer.domain.Beneficiary;

import java.util.UUID;

public record BeneficiaryView(UUID id, UUID customerId, String bankCode, String accountNumber, String accountName) {

    static BeneficiaryView from(Beneficiary beneficiary) {
        return new BeneficiaryView(beneficiary.getId(), beneficiary.getCustomerId(), beneficiary.getBankCode(),
                beneficiary.getAccountNumber(), beneficiary.getAccountName());
    }
}
