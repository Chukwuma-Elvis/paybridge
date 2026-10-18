package com.academy.paybridge.shared.web;

import com.academy.paybridge.shared.exception.ErrorCode;
import com.academy.paybridge.shared.exception.PayBridgeException;

import java.util.regex.Pattern;

/**
 * The {@code Idempotency-Key} header, for the endpoints that create something (a quote, a remittance). The transfer module has its
 * own copy of this rule for its endpoints.
 * <p>
 * A repeated request with the same key gets the original result back instead of creating a second one. Without the header a retry
 * after a lost reply could create a duplicate, so it is mandatory.
 */
public final class IdempotencyKeyHeader {

    public static final String NAME = "Idempotency-Key";

    private static final Pattern VALID = Pattern.compile("[A-Za-z0-9._:-]{8,100}");

    private IdempotencyKeyHeader() {
    }

    /** Returns the key, or throws a 400 if it is missing or malformed. */
    public static String require(String value) {
        if (value == null || value.isBlank()) {
            throw new PayBridgeException(ErrorCode.VALIDATION_FAILED,
                    "The " + NAME + " header is required (a unique value per request, 8 to 100 characters)");
        }
        if (!VALID.matcher(value).matches()) {
            throw new PayBridgeException(ErrorCode.VALIDATION_FAILED,
                    "The " + NAME + " header must be 8 to 100 characters: letters, digits, . _ : -");
        }
        return value;
    }
}
