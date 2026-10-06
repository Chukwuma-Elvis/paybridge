package com.academy.paybridge.account.domain;

import com.academy.paybridge.account.api.AccountStatus;
import com.academy.paybridge.account.api.AccountType;
import com.academy.paybridge.account.api.InsufficientFundsException;
import com.academy.paybridge.shared.audit.AuditableEntity;
import com.academy.paybridge.shared.exception.ErrorCode;
import com.academy.paybridge.shared.exception.PayBridgeException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.util.UUID;

@Entity
@Table(name = "account")
public class Account extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** Only customer accounts have a number. */
    @Column
    private String accountNumber;

    /** Only system accounts have a code. */
    @Column
    private String code;

    @Enumerated(EnumType.STRING)
    @Column(name = "account_type", nullable = false)
    private AccountType type;

    @Column
    private UUID ownerCustomerId;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(nullable = false)
    private long balanceMinor;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AccountStatus status;

    @Version
    @Column(nullable = false)
    private long version;

    protected Account() {
    }

    private Account(String accountNumber, UUID ownerCustomerId, String currency) {
        this.accountNumber = accountNumber;
        this.ownerCustomerId = ownerCustomerId;
        this.currency = currency;
        this.type = AccountType.CUSTOMER;
        this.status = AccountStatus.ACTIVE;
        this.balanceMinor = 0;
    }

    public static Account openForCustomer(String accountNumber, UUID customerId, String currencyCode) {
        return new Account(accountNumber, customerId, currencyCode);
    }

    /**
     * Adds {@code deltaMinor} to the balance (negative to debit).
     * Customer accounts may never go below zero.
     */
    public void applyBalanceChange(long deltaMinor) {
        if (status != AccountStatus.ACTIVE) {
            throw new PayBridgeException(ErrorCode.ACCOUNT_NOT_ACTIVE, "Account " + describe() + " is not active");
        }
        long newBalance = Math.addExact(balanceMinor, deltaMinor);
        if (type == AccountType.CUSTOMER && newBalance < 0) {
            throw new InsufficientFundsException("Insufficient funds in account " + describe());
        }
        this.balanceMinor = newBalance;
    }

    public void freeze() {
        this.status = AccountStatus.FROZEN;
    }

    public void activate() {
        this.status = AccountStatus.ACTIVE;
    }

    private String describe() {
        return accountNumber != null ? accountNumber : code;
    }

    public UUID getId() {
        return id;
    }

    public String getAccountNumber() {
        return accountNumber;
    }

    public String getCode() {
        return code;
    }

    public AccountType getType() {
        return type;
    }

    public UUID getOwnerCustomerId() {
        return ownerCustomerId;
    }

    public String getCurrency() {
        return currency;
    }

    public long getBalanceMinor() {
        return balanceMinor;
    }

    public AccountStatus getStatus() {
        return status;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof Account that)) {
            return false;
        }
        return id != null && id.equals(that.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
