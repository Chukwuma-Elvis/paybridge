package com.academy.paybridge.transfer.client;

import com.academy.paybridge.shared.exception.ErrorCode;
import com.academy.paybridge.shared.exception.PayBridgeException;

/**
 * We could not get a clear answer from the provider (timeout, network error, provider outage).
 * <p>
 * IMPORTANT: this does NOT mean the transfer failed. The provider may or may not have received the request, so the
 * money may or may not have moved. Callers must treat it as "unknown" and find out later, never as "failed".
 */
public class GatewayUnavailableException extends PayBridgeException {

    public GatewayUnavailableException(String message) {
        super(ErrorCode.GATEWAY_ERROR, message);
    }

    public GatewayUnavailableException(String message, Throwable cause) {
        super(ErrorCode.GATEWAY_ERROR, message);
        initCause(cause);
    }
}
