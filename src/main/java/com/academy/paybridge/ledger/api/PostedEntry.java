package com.academy.paybridge.ledger.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record PostedEntry(
        UUID id,
        EntryType type,
        String reference,
        String description,
        UUID reversesEntryId,
        Instant createdAt,
        List<PostingLine> lines) {
}
