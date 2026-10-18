package com.academy.paybridge.transfer.domain;

/**
 * Where a received webhook got to. Only {@link #RECEIVED} and {@link #FAILED} can be picked up and tried again;
 * every other state is final, so a provider repeating the event changes nothing.
 */
public enum WebhookStatus {
    /** Claimed and being processed. */
    RECEIVED,
    /** The outcome was applied to the transfer. */
    APPLIED,
    /** An event we do not act on (for example a payment-collection event). */
    IGNORED,
    /** It named a transfer we do not have, or that is not an external transfer. Nothing was changed. */
    UNMATCHED,
    /** The amount or currency did not match our record, so it was NOT applied. A person must look. */
    AMOUNT_MISMATCH,
    /** Applying it would contradict the transfer's state (for example a "success" for a transfer already refunded). A person must look. */
    MANUAL_REVIEW,
    /** Applying it failed unexpectedly. The provider's next delivery is tried again. */
    FAILED;

    /** True when a repeat of this event must be ignored. */
    public boolean isFinal() {
        return this != RECEIVED && this != FAILED;
    }
}
