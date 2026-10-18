package com.academy.paybridge.remittance.service;

import com.academy.paybridge.remittance.api.RemittanceView;
import com.academy.paybridge.remittance.domain.Remittance;

/** Turns the stored remittance into what other modules and clients may see: the account number masked to its last four digits. */
final class RemittanceMapper {

    private RemittanceMapper() {
    }

    static RemittanceView view(Remittance r) {
        return new RemittanceView(r.getId(), r.getReference(), r.getStatus(), r.getStatusReason(), r.getCorridor(),
                r.getSellMinor(), r.getFeeMinor(), r.getBuyMinor(), r.getQuoteId(), r.getPartnerCode(), r.getPartnerReference(),
                r.getSenderName(), r.getRecipientName(), r.getRecipientType(), mask(r.getRecipientAccountNumber()),
                r.getFundingExpiresAt(), r.getCreatedAt(), r.getPaidAt());
    }

    static String mask(String accountNumber) {
        if (accountNumber == null || accountNumber.length() <= 4) {
            return "****";
        }
        return "******" + accountNumber.substring(accountNumber.length() - 4);
    }
}
