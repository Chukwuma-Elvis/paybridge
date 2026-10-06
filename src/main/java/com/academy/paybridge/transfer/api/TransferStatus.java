package com.academy.paybridge.transfer.api;

/**
 * Where a transfer is in its life. Only some moves are legal:
 * <pre>
 *   PENDING ──► PROCESSING ──► SUCCESS ──► REVERSED
 *      │            │
 *      └────────────┴──────► FAILED
 * </pre>
 * (An internal transfer goes straight from PENDING to SUCCESS.) FAILED and REVERSED are final.
 */
public enum TransferStatus {
    PENDING,
    PROCESSING,
    SUCCESS,
    FAILED,
    REVERSED;

    public boolean canMoveTo(TransferStatus next) {
        return switch (this) {
            case PENDING -> next == PROCESSING || next == SUCCESS || next == FAILED;
            case PROCESSING -> next == SUCCESS || next == FAILED;
            case SUCCESS -> next == REVERSED;
            case FAILED, REVERSED -> false;
        };
    }

    public boolean isFinal() {
        return this == FAILED || this == REVERSED;
    }
}
