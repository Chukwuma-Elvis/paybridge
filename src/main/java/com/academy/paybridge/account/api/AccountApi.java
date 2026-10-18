package com.academy.paybridge.account.api;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * The front desk of the account module.
 * <p>
 * {@link #lockAccounts} and {@link #applyBalanceChange} change money and must only be called by the ledger
 * module, inside the ledger's transaction. Everyone else posts money through the ledger.
 */
public interface AccountApi {

    /** @throws com.academy.paybridge.shared.exception.ResourceNotFoundException if there is no such account */
    AccountSummary getAccount(UUID accountId);

    /** @throws com.academy.paybridge.shared.exception.ResourceNotFoundException if there is no such account */
    AccountSummary getByAccountNumber(String accountNumber);

    UUID systemAccountId(SystemAccount account);

    /** Finds one of the bank's own accounts by its code (for accounts that are created at run time, such as a partner's). */
    UUID systemAccountIdByCode(String code);

    /**
     * Returns the bank's own account with this code, creating it (empty, active, in this currency) if it does not exist yet.
     * Asking again returns the same account. If it exists in a different currency the request is refused.
     */
    UUID ensureSystemAccount(String code, String currencyCode);

    /**
     * Locks the accounts for the rest of the current transaction and returns their up-to-date state.
     * Rows are always locked in ascending id order, whatever order the caller lists them in, so two
     * transfers going in opposite directions can never deadlock.
     */
    List<AccountSummary> lockAccounts(Collection<UUID> accountIds);

    /**
     * Adds {@code deltaMinor} (negative to debit) to the account's balance. The account must already be
     * locked by {@link #lockAccounts} in the same transaction.
     *
     * @throws InsufficientFundsException if a customer account would go below zero
     * @throws com.academy.paybridge.shared.exception.PayBridgeException {@code ACCOUNT_NOT_ACTIVE} if frozen
     */
    void applyBalanceChange(UUID accountId, long deltaMinor);
}
