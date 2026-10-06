package com.academy.paybridge.ledger.api;

import java.time.Instant;
import java.util.UUID;

/** One row of an account statement. */
public record LedgerLineView(
        UUID entryId,
        EntryType type,
        String reference,
        String description,
        long amountMinor,
        String currency,
        Instant createdAt) {
}
