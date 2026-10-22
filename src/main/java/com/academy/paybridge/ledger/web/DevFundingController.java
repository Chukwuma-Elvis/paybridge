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
@RequestMapping("/api/dev/accounts")
@ConditionalOnExpression("${paybridge.dev.funding-enabled:false} == true or '${paybridge.gateway:fake}'.equalsIgnoreCase('fake')")
public class DevFundingController {

    private final LedgerApi ledgerApi;
    private final AccountApi accountApi;

    public DevFundingController(LedgerApi ledgerApi, AccountApi accountApi) {
        this.ledgerApi = ledgerApi;
        this.accountApi = accountApi;
    }

    @PostMapping("/{accountId}/fund")
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
}
