package com.academy.paybridge.transfer.web;

import com.academy.paybridge.shared.exception.ErrorCode;
import com.academy.paybridge.shared.exception.PayBridgeException;

import java.util.regex.Pattern;

final class IdempotencyHeader {

    static final String NAME = "Idempotency-Key";

    private static final Pattern VALID = Pattern.compile("[A-Za-z0-9._:-]{8,100}");

    private IdempotencyHeader() {
    }

    /** The header is mandatory: without it a retry could pay twice. */
    static String require(String value) {
        if (value == null || value.isBlank()) {
            throw new PayBridgeException(ErrorCode.VALIDATION_FAILED,
                    "The " + NAME + " header is required (a unique value per transfer, 8 to 100 characters)");
        }
        if (!VALID.matcher(value).matches()) {
            throw new PayBridgeException(ErrorCode.VALIDATION_FAILED,
                    "The " + NAME + " header must be 8 to 100 characters: letters, digits, . _ : -");
        }
        return value;
    }
}
