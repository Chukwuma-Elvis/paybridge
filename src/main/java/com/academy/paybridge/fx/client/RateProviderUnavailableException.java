package com.academy.paybridge.fx.client;

import com.academy.paybridge.shared.exception.ErrorCode;
import com.academy.paybridge.shared.exception.PayBridgeException;

/** The rate source gave no clear answer. The last good rate stays in force until it goes stale. */
public class RateProviderUnavailableException extends PayBridgeException {

    public RateProviderUnavailableException(String message) {
        super(ErrorCode.RATE_UNAVAILABLE, message);
    }
}
