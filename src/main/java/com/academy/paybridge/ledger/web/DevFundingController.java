package com.academy.paybridge.ledger.web;

import com.academy.paybridge.account.api.AccountApi;
import com.academy.paybridge.account.api.AccountSummary;
import com.academy.paybridge.account.api.SystemAccount;
import com.academy.paybridge.ledger.api.EntryType;
import com.academy.paybridge.ledger.api.LedgerApi;
import com.academy.paybridge.ledger.api.PostedEntry;
import com.academy.paybridge.ledger.api.PostingLine;
import com.academy.paybridge.ledger.api.PostingRequest;
import com.academy.paybridge.shared.exception.ErrorCode;
import com.academy.paybridge.shared.exception.PayBridgeException;
import com.academy.paybridge.shared.money.Money;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

/**
 * TEST SCAFFOLDING. There is no real deposit flow yet, so this puts test naira into an account by moving it
 * from the FUNDING_SOURCE system account. Active when {@code paybridge.dev.funding-enabled=true} or when running
 * with the fake sandbox gateway. Requires an ADMIN API key in all environments.
 */
@RestController
@RequestMapping("/api/dev")
@ConditionalOnExpression("${paybridge.dev.funding-enabled:false} == true or '${paybridge.gateway:fake}'.equalsIgnoreCase('fake')")
public class DevFundingController {

    private final LedgerApi ledgerApi;
    private final AccountApi accountApi;
    private final JdbcTemplate jdbc;

    public DevFundingController(LedgerApi ledgerApi, AccountApi accountApi, JdbcTemplate jdbc) {
        this.ledgerApi = ledgerApi;
        this.accountApi = accountApi;
        this.jdbc = jdbc;
    }

    @PostMapping("/accounts/{accountId}/fund")
    public Map<String, Object> fund(@PathVariable UUID accountId, @Valid @RequestBody FundAccountRequest request) {
        AccountSummary account = accountApi.getAccount(accountId);
        if (!account.isCustomerAccount()) {
            throw new PayBridgeException(ErrorCode.VALIDATION_FAILED, "Only customer accounts can be funded");
        }

        Money amount = Money.ofMinor(request.amountMinor(), account.currency());
        PostedEntry entry = ledgerApi.post(PostingRequest.of(
                EntryType.FUNDING,
                "funding:" + UUID.randomUUID(),
                "Dev funding",
                new PostingLine(accountApi.systemAccountId(SystemAccount.FUNDING_SOURCE), amount.negate()),
                new PostingLine(accountId, amount)));

        return Map.of(
                "entryId", entry.id(),
                "accountId", accountId,
                "balanceMinor", accountApi.getAccount(accountId).balanceMinor());
    }

    /**
     * Resets sandbox data: deletes all custom API keys (preserving bootstrap admin), clears all customer
     * accounts, customers, ledger entries, transfers, and resets system accounts back to zero balance.
     */
    @PostMapping("/reset")
    @Transactional
    public Map<String, Object> resetDemoData() {
        int deletedKeys = jdbc.update("DELETE FROM api_key WHERE name != 'bootstrap-admin' AND prefix != '(operator)'");

        // Break cyclic / foreign key dependencies before deletion
        jdbc.update("UPDATE journal_entry SET reverses_entry_id = NULL");

        // Clear dependent domain records
        jdbc.update("DELETE FROM settlement_break");
        jdbc.update("DELETE FROM settlement_batch_line");
        jdbc.update("DELETE FROM settlement_statement_line");
        jdbc.update("DELETE FROM settlement_statement");
        jdbc.update("DELETE FROM settlement_batch");
        jdbc.update("DELETE FROM remittance_event");
        jdbc.update("DELETE FROM remittance_claim");
        jdbc.update("DELETE FROM remittance");
        jdbc.update("DELETE FROM system_payout");
        jdbc.update("DELETE FROM fx_conversion");
        jdbc.update("DELETE FROM fx_quote");
        jdbc.update("DELETE FROM velocity_event");
        jdbc.update("DELETE FROM usd_usage");
        jdbc.update("DELETE FROM screening_case");
        jdbc.update("DELETE FROM transfer_outbox");
        jdbc.update("DELETE FROM idempotency_key");
        jdbc.update("DELETE FROM transfer");
        jdbc.update("DELETE FROM webhook_event");
        jdbc.update("DELETE FROM notification");
        jdbc.update("DELETE FROM daily_usage");
        jdbc.update("DELETE FROM beneficiary");
        jdbc.update("DELETE FROM ledger_line");
        jdbc.update("DELETE FROM journal_entry");

        // Delete customer accounts and reset system account balances
        int deletedAccounts = jdbc.update("DELETE FROM account WHERE account_type = 'CUSTOMER'");
        jdbc.update("UPDATE account SET balance_minor = 0 WHERE account_type = 'SYSTEM'");

        // Delete customers
        int deletedCustomers = jdbc.update("DELETE FROM customer");

        // Reset account number sequence
        try {
            jdbc.execute("ALTER SEQUENCE account_number_seq RESTART WITH 100000001");
        } catch (Exception ignored) {
        }

        return Map.of(
                "status", "SUCCESS",
                "deletedCustomers", deletedCustomers,
                "deletedAccounts", deletedAccounts,
                "deletedApiKeys", deletedKeys,
                "preservedKey", "bootstrap-admin"
        );
    }
}
