package com.academy.paybridge.account.api;

import com.academy.paybridge.shared.exception.ErrorCode;
import com.academy.paybridge.shared.exception.PayBridgeException;

public class InsufficientFundsException extends PayBridgeException {

    public InsufficientFundsException(String message) {
        super(ErrorCode.INSUFFICIENT_FUNDS, message);
    }
}
