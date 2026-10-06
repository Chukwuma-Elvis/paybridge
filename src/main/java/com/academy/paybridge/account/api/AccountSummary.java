package com.academy.paybridge.account.api;

import com.academy.paybridge.shared.money.Money;

import java.util.Currency;
import java.util.UUID;

/**
 * The read-only view of an account that other modules may use.
 * {@code accountNumber} and {@code ownerCustomerId} are null for system accounts; {@code code} is null for customer accounts.
 */
public record AccountSummary(
        UUID id,
        String accountNumber,
        String code,
        AccountType type,
        UUID ownerCustomerId,
        Currency currency,
        long balanceMinor,
        AccountStatus status) {

    public Money balance() {
        return Money.ofMinor(balanceMinor, currency);
    }

    public boolean isActive() {
        return status == AccountStatus.ACTIVE;
    }

    public boolean isCustomerAccount() {
        return type == AccountType.CUSTOMER;
    }
}
