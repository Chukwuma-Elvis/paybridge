package com.academy.paybridge.fx.api;

import java.util.Optional;
import java.util.UUID;

/**
 * What other modules may ask of the {@code fx} module.
 */
public interface FxApi {

    /**
     * The rate in force for the corridor. Throws a {@code RATE_UNAVAILABLE} error if there is no rate, or the latest one is
     * older than the allowed age: it is better to refuse a quote than to quote a stale rate.
     */
    CurrentRate currentRate(Corridor corridor);

    /**
     * Issues a quote for selling {@code sellMinor} (in the corridor's sold currency). Idempotent per ({@code requestedBy},
     * {@code idempotencyKey}): the same request again returns the same quote; the same key with a different request is refused.
     */
    QuoteIssued createQuote(Corridor corridor, long sellMinor, String requestedBy, String idempotencyKey);

    /**
     * Uses the quote for the given consumer (a remittance id). Succeeds at most once per quote, and only while it is open and not
     * expired. Asking again with the SAME consumer returns the already-consumed quote (so a retry is harmless); a different consumer
     * is refused.
     */
    Quote consumeQuote(UUID quoteId, UUID consumerId);

    Quote getQuote(UUID quoteId);

    /**
     * Carries out a CONSUMED quote in the ledger as two linked entries (dollars out of the dollar account, naira into the naira account),
     * both or neither. Asking again for the same quote returns the same conversion and moves nothing.
     */
    Conversion convert(UUID quoteId, UUID usdSourceAccountId, UUID ngnTargetAccountId);

    /** Undoes both legs together. Repeating it changes nothing. */
    Conversion reverseConversion(UUID quoteId, String reason);

    Optional<Conversion> findConversion(UUID quoteId);

    /** A quote plus whether this call created it (false when it is a replay). */
    record QuoteIssued(Quote quote, boolean created) {
    }
}
