package com.academy.paybridge.remittance.api;

/**
 * Where a remittance is in its life. The only legal moves:
 * <pre>
 *   CREATED ──► SCREENING ──► AWAITING_FUNDING ──► FUNDED ──► CONVERTED ──► PAYOUT_PENDING ──► PAID
 *                  │  ▲              │                │           │               │
 *                  ▼  │              │                │           │               │
 *             UNDER_REVIEW ──────────┤                └───────────┴───────────────┴──► REFUND_PENDING ──► REFUNDED
 *                  │                 │
 *                  └─────────────────┴──► FAILED / CANCELLED          (CONVERTED may also go straight to PAID: internal payout)
 * </pre>
 * PAID, FAILED, REFUNDED and CANCELLED are final. Money has moved only from FUNDED onwards.
 */
public enum RemittanceStatus {
    /** Recorded and validated; a quote is attached. */
    CREATED,
    /** The sender and recipient are being screened. */
    SCREENING,
    /** A screening hit or a velocity flag needs a person. Nothing moves until a decision. */
    UNDER_REVIEW,
    /** Cleared; waiting for the partner to confirm the sender's USD. */
    AWAITING_FUNDING,
    /** The partner confirmed the USD and the funding entry is posted. */
    FUNDED,
    /** The two conversion legs are posted. */
    CONVERTED,
    /** An external naira payout has been started. */
    PAYOUT_PENDING,
    /** The recipient has the money. Final. */
    PAID,
    /** Stopped before any money moved. Final. */
    FAILED,
    /** The payout failed after funding; the sender's USD is being returned. */
    REFUND_PENDING,
    /** The sender's USD was returned. Final. */
    REFUNDED,
    /** Cancelled by an admin before funding. Final. */
    CANCELLED;

    public boolean canMoveTo(RemittanceStatus next) {
        return switch (this) {
            case CREATED -> next == SCREENING || next == FAILED;
            case SCREENING -> next == UNDER_REVIEW || next == AWAITING_FUNDING || next == FAILED;
            case UNDER_REVIEW -> next == AWAITING_FUNDING || next == FAILED || next == CANCELLED;
            case AWAITING_FUNDING -> next == FUNDED || next == UNDER_REVIEW || next == FAILED || next == CANCELLED;
            case FUNDED -> next == CONVERTED || next == REFUND_PENDING;
            case CONVERTED -> next == PAYOUT_PENDING || next == PAID || next == REFUND_PENDING;
            case PAYOUT_PENDING -> next == PAID || next == REFUND_PENDING;
            case REFUND_PENDING -> next == REFUNDED;
            case PAID, FAILED, REFUNDED, CANCELLED -> false;
        };
    }

    public boolean isFinal() {
        return this == PAID || this == FAILED || this == REFUNDED || this == CANCELLED;
    }

    /** True once the sender's money has been taken in, so a refund is owed if the remittance cannot complete. */
    public boolean isFunded() {
        return this == FUNDED || this == CONVERTED || this == PAYOUT_PENDING || this == REFUND_PENDING;
    }
}
