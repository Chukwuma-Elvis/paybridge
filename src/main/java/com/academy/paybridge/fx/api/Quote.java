package com.academy.paybridge.fx.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A promise: "until {@code expiresAt}, {@code sellMinor} of the sold currency buys {@code buyMinor} of the bought currency at
 * {@code customerRate}." All amounts are whole minor units (cents, kobo).
 *
 * @param sellMinor     what the sender pays in total, in the sold currency (for example USD cents)
 * @param feeMinor      our fee, taken out of the amount sold (same currency)
 * @param buyMinor      what the recipient receives, in the bought currency (for example kobo): (sell - fee) x customer rate, rounded down
 * @param midRate       the mid-market rate the quote was based on
 * @param customerRate  the rate applied: mid less our spread
 * @param rateSource    where the mid rate came from
 * @param rateAsOf      the moment the mid rate was true
 * @param status        open, consumed, cancelled or expired
 * @param consumedBy    the remittance that used it, if any
 */
public record Quote(UUID id, String reference, Corridor corridor, long sellMinor, long feeMinor, long buyMinor,
                    BigDecimal midRate, BigDecimal customerRate, int spreadBps, String rateSource, Instant rateAsOf,
                    Instant expiresAt, QuoteStatus status, UUID consumedBy) {

    /** The part of the sold amount that is actually converted. */
    public long convertedMinor() {
        return sellMinor - feeMinor;
    }
}
