package com.academy.paybridge.ledger.api;

import java.util.List;

/**
 * Everything the ledger needs to record one business event.
 * <p>
 * {@code reference} identifies the event ("transfer 123, hold stage"). Posting the same
 * {@code (type, reference)} twice never moves money twice.
 */
public record PostingRequest(EntryType type, String reference, String description, List<PostingLine> lines) {

    public static PostingRequest of(EntryType type, String reference, String description, PostingLine... lines) {
        return new PostingRequest(type, reference, description, List.of(lines));
    }
}
