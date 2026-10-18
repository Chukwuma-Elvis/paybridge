package com.academy.paybridge.fx.api;

/** Where a quote stands. {@code EXPIRED} is worked out from the clock, never stored. */
public enum QuoteStatus {
    /** Can still be used. */
    OPEN,
    /** Used by a remittance. Final. */
    CONSUMED,
    /** Withdrawn before use. Final. */
    CANCELLED,
    /** Its time ran out before it was used. */
    EXPIRED
}
