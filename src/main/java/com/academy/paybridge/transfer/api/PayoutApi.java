package com.academy.paybridge.transfer.api;

import java.util.Optional;
import java.util.UUID;

/**
 * Sending naira to someone at another bank when the money comes from one of the bank's own accounts, not from a customer
 * (a remittance paying its recipient). It reuses the provider connection, the hold-and-settle ledger pattern, the dispatcher, the
 * reconciler and the webhooks of external transfers; only the customer-specific checks are skipped.
 */
public interface PayoutApi {

    /**
     * Starts a payout: parks the money from {@code sourceAccountId} in suspense and queues the send. Idempotent per {@code reference}:
     * the same reference with the same details returns the existing payout; different details are a CONFLICT.
     *
     * @param reference must start with {@code PO-}; it is the reference the provider is given
     */
    PayoutView request(PayoutRequest request);

    Optional<PayoutView> find(String reference);

    record PayoutRequest(String reference, UUID sourceAccountId, long amountMinor, String bankCode, String accountNumber,
                         String accountName, String narration) {
    }

    enum PayoutStatus {
        /** Queued; the provider has not been asked yet. */
        PENDING,
        /** Sent or being sent; the final answer is awaited. */
        PROCESSING,
        /** The recipient's bank has the money. Final. */
        SUCCESS,
        /** Refused or never sent; the money is back in the source account. Final. */
        FAILED;

        public boolean isFinal() {
            return this == SUCCESS || this == FAILED;
        }
    }

    record PayoutView(String reference, PayoutStatus status, long amountMinor, String failureReason) {
    }
}
