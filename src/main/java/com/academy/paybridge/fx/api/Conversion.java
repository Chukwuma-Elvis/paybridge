package com.academy.paybridge.fx.api;

import java.time.Instant;
import java.util.UUID;

/**
 * A quote that has been carried out in the ledger: two entries, one per currency, linked here.
 *
 * @param sellMinor   what left the USD clearing account
 * @param feeMinor    the part of it we kept as our fee
 * @param buyMinor    what entered the NGN clearing account
 * @param usdEntryId  the ledger entry that moved the dollars
 * @param ngnEntryId  the ledger entry that moved the naira
 * @param reversed    true once both legs have been undone together
 */
public record Conversion(UUID id, UUID quoteId, long sellMinor, long feeMinor, long buyMinor, UUID usdEntryId, UUID ngnEntryId,
                         boolean reversed, Instant createdAt) {
}
