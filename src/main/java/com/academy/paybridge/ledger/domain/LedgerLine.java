package com.academy.paybridge.ledger.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;
import java.util.UUID;

/** One debit (negative) or credit (positive) inside a journal entry. Immutable. */
@Entity
@Immutable
@Table(name = "ledger_line")
@EntityListeners(AuditingEntityListener.class)
public class LedgerLine {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, updatable = false)
    private UUID entryId;

    @Column(nullable = false, updatable = false)
    private UUID accountId;

    @Column(nullable = false, updatable = false)
    private long amountMinor;

    @Column(nullable = false, updatable = false, length = 3)
    private String currency;

    @CreatedDate
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    protected LedgerLine() {
    }

    public LedgerLine(UUID entryId, UUID accountId, long amountMinor, String currency) {
        this.entryId = entryId;
        this.accountId = accountId;
        this.amountMinor = amountMinor;
        this.currency = currency;
    }

    public UUID getId() {
        return id;
    }

    public UUID getEntryId() {
        return entryId;
    }

    public UUID getAccountId() {
        return accountId;
    }

    public long getAmountMinor() {
        return amountMinor;
    }

    public String getCurrency() {
        return currency;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
