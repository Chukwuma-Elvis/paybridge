package com.academy.paybridge.transfer.service;

import com.academy.paybridge.transfer.api.TransferCompleted;
import com.academy.paybridge.transfer.api.TransferFailed;
import com.academy.paybridge.transfer.api.TransferReversed;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Counts transfer outcomes, for the {@code paybridge.transfers} metric (tagged by type and outcome).
 * <p>
 * It counts only AFTER the transaction has committed. A transfer that is rolled back never happened, so it must not be
 * counted; counting inside the transaction would over-count every refused attempt.
 */
@Component
class TransferMetrics {

    private final MeterRegistry meters;

    TransferMetrics(MeterRegistry meters) {
        this.meters = meters;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void on(TransferCompleted event) {
        count(event.type().name(), "success");
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void on(TransferFailed event) {
        count("EXTERNAL", "failed");
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void on(TransferReversed event) {
        count("EXTERNAL", "reversed");
    }

    private void count(String type, String outcome) {
        meters.counter("paybridge.transfers", "type", type, "outcome", outcome).increment();
    }
}
