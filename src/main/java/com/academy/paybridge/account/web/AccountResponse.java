package com.academy.paybridge.account.web;

import com.academy.paybridge.account.api.AccountStatus;
import com.academy.paybridge.account.api.AccountSummary;

import java.util.UUID;

public record AccountResponse(
        UUID id,
        String accountNumber,
        UUID customerId,
        String currency,
        long balanceMinor,
        AccountStatus status) {

    static AccountResponse from(AccountSummary summary) {
        return new AccountResponse(
                summary.id(),
                summary.accountNumber(),
                summary.ownerCustomerId(),
                summary.currency().getCurrencyCode(),
                summary.balanceMinor(),
                summary.status());
    }
}
