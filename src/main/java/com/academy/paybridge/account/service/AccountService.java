package com.academy.paybridge.account.service;

import com.academy.paybridge.account.api.AccountApi;
import com.academy.paybridge.account.api.AccountSummary;
import com.academy.paybridge.account.api.AccountType;
import com.academy.paybridge.account.api.SystemAccount;
import com.academy.paybridge.account.domain.Account;
import com.academy.paybridge.account.domain.Nuban;
import com.academy.paybridge.account.repository.AccountRepository;
import com.academy.paybridge.customer.api.CustomerApi;
import com.academy.paybridge.customer.api.CustomerSummary;
import com.academy.paybridge.shared.exception.ErrorCode;
import com.academy.paybridge.shared.exception.PayBridgeException;
import com.academy.paybridge.shared.exception.ResourceNotFoundException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Currency;
import java.util.List;
import java.util.Optional;
import java.util.TreeSet;
import java.util.UUID;

@Service
@Transactional
public class AccountService implements AccountApi {

    /** The {@code code} column holds at most 40 characters. */
    private static final java.util.regex.Pattern SYSTEM_ACCOUNT_CODE = java.util.regex.Pattern.compile("^[A-Za-z0-9_:.-]{1,40}$");

    private final AccountRepository accountRepository;
    private final CustomerApi customerApi;
    private final String bankCode;
    private final boolean usdAccountsEnabled;

    public AccountService(AccountRepository accountRepository,
                          CustomerApi customerApi,
                          @Value("${paybridge.account.bank-code:999}") String bankCode,
                          @Value("${paybridge.fx.usd-accounts-enabled:false}") boolean usdAccountsEnabled) {
        this.accountRepository = accountRepository;
        this.customerApi = customerApi;
        this.bankCode = bankCode;
        this.usdAccountsEnabled = usdAccountsEnabled;
    }

    public AccountSummary openCustomerAccount(UUID customerId, String currencyCode) {
        boolean supported = "NGN".equals(currencyCode) || (usdAccountsEnabled && "USD".equals(currencyCode));
        if (!supported) {
            throw new PayBridgeException(ErrorCode.VALIDATION_FAILED, usdAccountsEnabled
                    ? "Only NGN and USD accounts are supported"
                    : "Only NGN accounts are supported at the moment");
        }
        CustomerSummary customer = customerApi.getCustomer(customerId);
        if (!customer.isActive()) {
            throw new PayBridgeException(ErrorCode.ACCOUNT_NOT_ACTIVE,
                    "Customer " + customerId + " is not active, so no account can be opened");
        }

        String accountNumber = Nuban.generate(bankCode, accountRepository.nextAccountSerial());
        Account account = accountRepository.save(Account.openForCustomer(accountNumber, customerId, currencyCode));
        return toSummary(account);
    }

    @Transactional(readOnly = true)
    public List<AccountSummary> listRecentCustomerAccounts() {
        return accountRepository.findByTypeOrderByCreatedAtDesc(AccountType.CUSTOMER).stream()
                .map(AccountService::toSummary)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<AccountSummary> listForCustomer(UUID customerId) {
        return accountRepository.findByOwnerCustomerIdOrderByCreatedAtAsc(customerId).stream()
                .map(AccountService::toSummary)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public AccountSummary getAccount(UUID accountId) {
        return toSummary(accountRepository.findById(accountId)
                .orElseThrow(() -> new ResourceNotFoundException("Account", accountId)));
    }

    @Override
    @Transactional(readOnly = true)
    public AccountSummary getByAccountNumber(String accountNumber) {
        return toSummary(accountRepository.findByAccountNumber(accountNumber)
                .orElseThrow(() -> new ResourceNotFoundException("Account", accountNumber)));
    }

    @Override
    @Transactional(readOnly = true)
    public UUID systemAccountId(SystemAccount systemAccount) {
        return accountRepository.findIdByCode(systemAccount.name())
                .orElseThrow(() -> new ResourceNotFoundException("System account", systemAccount));
    }

    @Override
    @Transactional(readOnly = true)
    public UUID systemAccountIdByCode(String code) {
        return accountRepository.findIdByCode(code)
                .orElseThrow(() -> new ResourceNotFoundException("System account", code));
    }

    @Override
    public UUID ensureSystemAccount(String code, String currencyCode) {
        if (!"NGN".equals(currencyCode) && !"USD".equals(currencyCode)) {
            throw new PayBridgeException(ErrorCode.VALIDATION_FAILED, "Unsupported currency " + currencyCode);
        }
        if (code == null || !SYSTEM_ACCOUNT_CODE.matcher(code).matches()) {
            throw new PayBridgeException(ErrorCode.VALIDATION_FAILED,
                    "A system account code must be 1 to 40 letters, digits, '_', ':', '.' or '-'");
        }
        // "create it unless it exists" in one statement: two callers at the same instant cannot both create it
        accountRepository.createSystemAccountIfAbsent(code, currencyCode);
        String existingCurrency = accountRepository.findCurrencyByCode(code)
                .orElseThrow(() -> new IllegalStateException("System account " + code + " vanished right after being created"));
        if (!existingCurrency.equals(currencyCode)) {
            throw new PayBridgeException(ErrorCode.CONFLICT, "System account " + code + " already exists in "
                    + existingCurrency + ", not " + currencyCode);
        }
        return accountRepository.findIdByCode(code).orElseThrow();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public List<AccountSummary> lockAccounts(Collection<UUID> accountIds) {
        List<AccountSummary> locked = new ArrayList<>();
        // TreeSet = ascending id order, whatever order the caller used: this is what prevents deadlocks
        for (UUID id : new TreeSet<>(accountIds)) {
            Account account = accountRepository.findByIdForUpdate(id)
                    .orElseThrow(() -> new ResourceNotFoundException("Account", id));
            locked.add(toSummary(account));
        }
        return locked;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void applyBalanceChange(UUID accountId, long deltaMinor) {
        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new ResourceNotFoundException("Account", accountId));
        account.applyBalanceChange(deltaMinor);
    }

    private static AccountSummary toSummary(Account account) {
        return new AccountSummary(
                account.getId(),
                account.getAccountNumber(),
                account.getCode(),
                account.getType(),
                account.getOwnerCustomerId(),
                Currency.getInstance(account.getCurrency()),
                account.getBalanceMinor(),
                account.getStatus());
    }
}
