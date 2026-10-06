package com.academy.paybridge.ledger.api;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The front desk of the ledger. This is the ONLY way money moves between accounts.
 * <p>
 * Posting joins the caller's transaction, so a business change (such as a transfer's new state) and the
 * money movement it describes commit together or not at all.
 */
public interface LedgerApi {

    /**
     * Records a balanced journal entry and applies it to the account balances.
     * <ul>
     *   <li>If an entry with the same type and reference already exists with the same lines, that entry is
     *       returned and nothing moves (safe retry).</li>
     *   <li>If it exists with different lines, a {@code CONFLICT} is raised.</li>
     * </ul>
     *
     * @throws com.academy.paybridge.shared.exception.PayBridgeException {@code VALIDATION_FAILED} if the lines do not balance
     * @throws com.academy.paybridge.account.api.InsufficientFundsException if a customer account would go below zero
     */
    PostedEntry post(PostingRequest request);

    /**
     * Posts a REVERSAL entry that exactly undoes {@code entryId}. Idempotent per {@code reference}.
     */
    PostedEntry reverse(UUID entryId, String reference, String description);

    Optional<PostedEntry> find(EntryType type, String reference);

    /** Newest first. */
    List<LedgerLineView> statement(UUID accountId, int limit);

    /** The sum of every line ever posted to the account. Must always equal the account's cached balance. */
    long sumOfLines(UUID accountId);
}
