package com.academy.paybridge.ledger.domain;

import com.academy.paybridge.ledger.api.EntryType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;
import java.util.UUID;

/** One business event. Immutable: once written it is never changed (the database enforces this too). */
@Entity
@Immutable
@Table(name = "journal_entry")
@EntityListeners(AuditingEntityListener.class)
public class JournalEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "entry_type", nullable = false, updatable = false)
    private EntryType entryType;

    @Column(nullable = false, updatable = false)
    private String reference;

    @Column(updatable = false)
    private String description;

    @Column(updatable = false)
    private UUID reversesEntryId;

    @CreatedDate
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    protected JournalEntry() {
    }

    public JournalEntry(EntryType entryType, String reference, String description, UUID reversesEntryId) {
        this.entryType = entryType;
        this.reference = reference;
        this.description = description;
        this.reversesEntryId = reversesEntryId;
    }

    public UUID getId() {
        return id;
    }

    public EntryType getEntryType() {
        return entryType;
    }

    public String getReference() {
        return reference;
    }

    public String getDescription() {
        return description;
    }

    public UUID getReversesEntryId() {
        return reversesEntryId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
