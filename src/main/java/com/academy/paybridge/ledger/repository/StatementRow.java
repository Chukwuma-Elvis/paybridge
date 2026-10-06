package com.academy.paybridge.ledger.repository;

import com.academy.paybridge.ledger.api.EntryType;

import java.time.Instant;
import java.util.UUID;

/** A statement line as read straight from the database (a query projection). */
public record StatementRow(
        UUID entryId,
        EntryType entryType,
        String reference,
        String description,
        long amountMinor,
        String currency,
        Instant createdAt) {
}
