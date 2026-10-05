package com.academy.paybridge.shared.exception;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PayBridgeExceptionTest {

    @Test
    void carriesTheErrorCodeAndMessage() {
        PayBridgeException exception =
                new PayBridgeException(ErrorCode.RESOURCE_NOT_FOUND, "Account 123 not found");

        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);
        assertThat(exception.getMessage()).isEqualTo("Account 123 not found");
    }

    @Test
    void isAnUncheckedException() {
        PayBridgeException exception =
                new PayBridgeException(ErrorCode.CONFLICT, "Already exists");

        assertThat(exception).isInstanceOf(RuntimeException.class);
    }

    @Test
    void canBeThrownAndCaughtWithItsCodeIntact() {
        assertThatThrownBy(() -> {
            throw new PayBridgeException(ErrorCode.VALIDATION_FAILED, "Amount is missing");
        })
                .isInstanceOf(PayBridgeException.class)
                .hasMessage("Amount is missing");
    }
}