package com.academy.paybridge.transfer;

import com.academy.paybridge.shared.exception.ErrorCode;
import com.academy.paybridge.shared.exception.PayBridgeException;
import com.academy.paybridge.shared.money.Money;
import com.academy.paybridge.transfer.api.TransferStatus;
import com.academy.paybridge.transfer.domain.Transfer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TransferStatusTest {

    @ParameterizedTest
    @CsvSource({
            "PENDING, PROCESSING, true",
            "PENDING, SUCCESS, true",
            "PENDING, FAILED, true",
            "PENDING, REVERSED, false",
            "PENDING, PENDING, false",
            "PROCESSING, SUCCESS, true",
            "PROCESSING, FAILED, true",
            "PROCESSING, PENDING, false",
            "PROCESSING, REVERSED, false",
            "SUCCESS, REVERSED, true",
            "SUCCESS, FAILED, false",
            "SUCCESS, PROCESSING, false",
            "SUCCESS, PENDING, false",
            "FAILED, SUCCESS, false",
            "FAILED, PROCESSING, false",
            "FAILED, REVERSED, false",
            "REVERSED, SUCCESS, false",
            "REVERSED, FAILED, false"
    })
    void onlyLegalMovesAreAllowed(TransferStatus from, TransferStatus to, boolean allowed) {
        assertThat(from.canMoveTo(to)).isEqualTo(allowed);
    }

    @Test
    void failedAndReversedAreFinal() {
        assertThat(TransferStatus.FAILED.isFinal()).isTrue();
        assertThat(TransferStatus.REVERSED.isFinal()).isTrue();
        assertThat(TransferStatus.SUCCESS.isFinal()).isFalse();
        assertThat(TransferStatus.PENDING.isFinal()).isFalse();
    }

    // ---- the same rules, enforced by the entity itself ----

    private static Transfer newTransfer() {
        return Transfer.internal(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                Money.ngn(1_000), "test", LocalDate.now());
    }

    @Test
    void aNewTransferStartsPending() {
        Transfer transfer = newTransfer();

        assertThat(transfer.getStatus()).isEqualTo(TransferStatus.PENDING);
        assertThat(transfer.getReference()).startsWith("pb-").hasSize(39);
        assertThat(transfer.getFeeMinor()).isZero();
    }

    @Test
    void successRecordsWhenItHappened() {
        Transfer transfer = newTransfer();
        Instant now = Instant.parse("2026-10-06T10:00:00Z");

        transfer.markSuccess(now);

        assertThat(transfer.getStatus()).isEqualTo(TransferStatus.SUCCESS);
        assertThat(transfer.getCompletedAt()).isEqualTo(now);
    }

    @Test
    void aFinishedTransferCannotBeSuccessfulAgainOrFailAfterwards() {
        Transfer transfer = newTransfer();
        transfer.markSuccess(Instant.now());

        assertThatThrownBy(() -> transfer.markSuccess(Instant.now()))
                .isInstanceOfSatisfying(PayBridgeException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_STATE));
        assertThatThrownBy(() -> transfer.markFailed("too late", Instant.now()))
                .isInstanceOf(PayBridgeException.class)
                .hasMessageContaining("SUCCESS to FAILED");
        assertThat(transfer.getStatus()).isEqualTo(TransferStatus.SUCCESS);
    }

    @Test
    void aFailedTransferStaysFailed() {
        Transfer transfer = newTransfer();
        transfer.markProcessing();
        transfer.markFailed("bank said no", Instant.now());

        assertThatThrownBy(() -> transfer.markSuccess(Instant.now())).isInstanceOf(PayBridgeException.class);
        assertThatThrownBy(() -> transfer.markReversed("x", Instant.now())).isInstanceOf(PayBridgeException.class);
        assertThat(transfer.getFailureReason()).isEqualTo("bank said no");
    }

    @Test
    void aSuccessfulTransferCanLaterBeReversed() {
        Transfer transfer = newTransfer();
        transfer.markProcessing();
        transfer.markSuccess(Instant.now());

        transfer.markReversed("recipient bank returned it", Instant.now());

        assertThat(transfer.getStatus()).isEqualTo(TransferStatus.REVERSED);
    }

    @Test
    void aVeryLongFailureReasonIsCutToFitTheColumn() {
        Transfer transfer = newTransfer();

        transfer.markFailed("x".repeat(1_000), Instant.now());

        assertThat(transfer.getFailureReason()).hasSize(255);
    }

    @Test
    void theMoneyHelpersReturnMoneyInTheTransfersCurrency() {
        Transfer transfer = newTransfer();

        assertThat(transfer.amount()).isEqualTo(Money.ngn(1_000));
        assertThat(transfer.fee()).isEqualTo(Money.ngn(0));
    }
}
