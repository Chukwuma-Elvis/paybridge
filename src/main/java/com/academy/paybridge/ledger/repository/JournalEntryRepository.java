package com.academy.paybridge.ledger.repository;

import com.academy.paybridge.ledger.api.EntryType;
import com.academy.paybridge.ledger.domain.JournalEntry;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface JournalEntryRepository extends JpaRepository<JournalEntry, UUID> {

    Optional<JournalEntry> findByEntryTypeAndReference(EntryType entryType, String reference);

    boolean existsByReversesEntryId(UUID reversesEntryId);
}
