package com.academy.paybridge.ledger.web;

import com.academy.paybridge.ledger.api.EntryType;
import com.academy.paybridge.ledger.api.LedgerLineView;

import java.time.Instant;
import java.util.UUID;

public record StatementLineResponse(
        UUID entryId,
        EntryType type,
        String reference,
        String description,
        long amountMinor,
        String currency,
        Instant createdAt) {

    static StatementLineResponse from(LedgerLineView view) {
        return new StatementLineResponse(view.entryId(), view.type(), view.reference(), view.description(),
                view.amountMinor(), view.currency(), view.createdAt());
    }
}
