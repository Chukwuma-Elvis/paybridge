package com.academy.paybridge.transfer.domain;

import com.academy.paybridge.shared.audit.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.UUID;

/** A payee at another bank that a customer has saved. The account name comes from the bank, not from the customer. */
@Entity
@Table(name = "beneficiary")
public class Beneficiary extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, updatable = false)
    private UUID customerId;

    @Column(nullable = false, updatable = false)
    private String bankCode;

    @Column(nullable = false, updatable = false)
    private String accountNumber;

    @Column(nullable = false, updatable = false)
    private String accountName;

    /** The provider's code for this payee (Paystack: a "recipient code"). */
    @Column(updatable = false)
    private String providerRecipientCode;

    protected Beneficiary() {
    }

    public Beneficiary(UUID customerId, String bankCode, String accountNumber, String accountName,
                       String providerRecipientCode) {
        this.customerId = customerId;
        this.bankCode = bankCode;
        this.accountNumber = accountNumber;
        this.accountName = accountName;
        this.providerRecipientCode = providerRecipientCode;
    }

    public UUID getId() {
        return id;
    }

    public UUID getCustomerId() {
        return customerId;
    }

    public String getBankCode() {
        return bankCode;
    }

    public String getAccountNumber() {
        return accountNumber;
    }

    public String getAccountName() {
        return accountName;
    }

    public String getProviderRecipientCode() {
        return providerRecipientCode;
    }
}
