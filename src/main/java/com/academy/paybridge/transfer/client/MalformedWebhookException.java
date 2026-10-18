package com.academy.paybridge.transfer.client;

import com.academy.paybridge.shared.exception.ErrorCode;
import com.academy.paybridge.shared.exception.PayBridgeException;

/** The webhook was signed correctly but its body is not the JSON we expected. Answered with 400. */
public class MalformedWebhookException extends PayBridgeException {

    public MalformedWebhookException(String message) {
        super(ErrorCode.VALIDATION_FAILED, message);
    }

    public MalformedWebhookException(String message, Throwable cause) {
        super(ErrorCode.VALIDATION_FAILED, message);
        initCause(cause);
    }
}
