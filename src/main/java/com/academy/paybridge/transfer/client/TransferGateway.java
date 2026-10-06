package com.academy.paybridge.transfer.client;

/**
 * The seam between PayBridge and a payment provider (Paystack, Flutterwave, ...). The rest of the transfer module
 * only ever sees this interface, so swapping the provider means writing one new class, not touching the business logic.
 * <p>
 * Contract every implementation must keep:
 * <ul>
 *   <li>{@link #initiateTransfer} is idempotent per {@code reference}: calling it again with the same reference
 *       never sends the money twice.</li>
 *   <li>If the outcome is unclear (timeout, outage) throw {@link GatewayUnavailableException}. Never guess.</li>
 *   <li>A definite refusal is returned as {@link GatewayOutcome#FAILED}, not thrown.</li>
 * </ul>
 */
public interface TransferGateway {

    /** Name enquiry: who owns this account at that bank? Throws a RESOURCE_NOT_FOUND error if it cannot be resolved. */
    ResolvedAccount resolveAccount(String bankCode, String accountNumber);

    /** Registers the payee with the provider and returns the provider's code for it. */
    String createRecipient(String bankCode, String accountNumber, String accountName);

    /** Asks the provider to send the money. */
    GatewayTransferResult initiateTransfer(TransferInstruction instruction);

    /** Asks the provider what happened to a transfer, by our reference. */
    GatewayTransferResult fetchTransferStatus(String reference);
}
