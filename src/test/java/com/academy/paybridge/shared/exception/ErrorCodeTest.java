package com.academy.paybridge.shared.exception;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;

class ErrorCodeTest {

    @ParameterizedTest
    @CsvSource({
            "VALIDATION_FAILED, BAD_REQUEST",
            "CURRENCY_MISMATCH, BAD_REQUEST",
            "RESOURCE_NOT_FOUND, NOT_FOUND",
            "CONFLICT, CONFLICT",
            "UNAUTHORIZED, UNAUTHORIZED",
            "FORBIDDEN, FORBIDDEN",
            "RATE_LIMITED, TOO_MANY_REQUESTS",
            "RATE_UNAVAILABLE, SERVICE_UNAVAILABLE",
            "PAYLOAD_TOO_LARGE, CONTENT_TOO_LARGE",
            "INTERNAL_ERROR, INTERNAL_SERVER_ERROR"
    })
    void mapsEachCodeToItsHttpStatus(ErrorCode code, HttpStatus expected) {
        assertThat(code.getHttpStatus()).isEqualTo(expected);
    }

    @ParameterizedTest
    @EnumSource(ErrorCode.class)
    void everyCodeMapsToAnErrorStatus(ErrorCode code) {
        assertThat(code.getHttpStatus().isError()).isTrue();
    }
}