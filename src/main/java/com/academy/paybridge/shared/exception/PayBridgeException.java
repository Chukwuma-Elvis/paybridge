package com.academy.paybridge.shared.exception;

public class PayBridgeException extends RuntimeException {

    private final ErrorCode errorCode;

    public PayBridgeException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }
}