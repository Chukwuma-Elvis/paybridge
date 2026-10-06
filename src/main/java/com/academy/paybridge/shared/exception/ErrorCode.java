package com.academy.paybridge.shared.exception;

import org.springframework.http.HttpStatus;

public enum ErrorCode {

    VALIDATION_FAILED(HttpStatus.BAD_REQUEST),
    CURRENCY_MISMATCH(HttpStatus.BAD_REQUEST),
    RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND),
    CONFLICT(HttpStatus.CONFLICT),
    INSUFFICIENT_FUNDS(HttpStatus.valueOf(422)),
    LIMIT_EXCEEDED(HttpStatus.valueOf(422)),
    ACCOUNT_NOT_ACTIVE(HttpStatus.valueOf(422)),
    INVALID_STATE(HttpStatus.CONFLICT),
    IDEMPOTENCY_KEY_REUSED(HttpStatus.valueOf(422)),
    IDEMPOTENCY_KEY_IN_PROGRESS(HttpStatus.CONFLICT),
    GATEWAY_ERROR(HttpStatus.BAD_GATEWAY),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR);

    private final HttpStatus httpStatus;

    ErrorCode(HttpStatus httpStatus) {
        this.httpStatus = httpStatus;
    }

    public HttpStatus getHttpStatus() {
        return httpStatus;
    }
}
