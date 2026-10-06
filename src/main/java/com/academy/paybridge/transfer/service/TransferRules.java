package com.academy.paybridge.transfer.service;

import com.academy.paybridge.account.api.AccountSummary;
import com.academy.paybridge.shared.exception.ErrorCode;
import com.academy.paybridge.shared.exception.PayBridgeException;

/** Checks shared by internal and external transfers. */
final class TransferRules {

    private TransferRules() {
    }

    static void requireUsableCustomerAccount(AccountSummary account, String role) {
        if (!account.isCustomerAccount()) {
            throw new PayBridgeException(ErrorCode.VALIDATION_FAILED, role + " must be a customer account");
        }
        if (!account.isActive()) {
            throw new PayBridgeException(ErrorCode.ACCOUNT_NOT_ACTIVE, role + " account is not active");
        }
    }
}
