package com.academy.paybridge.shared.exception;

public class ConflictException extends PayBridgeException {

    public ConflictException(String message) {
        super(ErrorCode.CONFLICT, message);
    }
}
