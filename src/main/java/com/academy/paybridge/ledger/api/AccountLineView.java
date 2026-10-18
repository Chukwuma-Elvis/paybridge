package com.academy.paybridge.ledger.api;

import java.time.Instant;
import java.util.UUID;

/**
 * One line of an account as the ledger holds it, with the ids needed to point at it later.
 *
 * @param lineId  the line itself (what settlement batches refer to)
 * @param entryId the journal entry the line belongs to
 * @param type    what kind of business event the entry records
 * @param reference the entry's reference (for example a remittance reference)
 * @param amountMinor negative debits the account, positive credits it
 */
public record AccountLineView(UUID lineId, UUID entryId, EntryType type, String reference, long amountMinor, String currency,
                              Instant createdAt) {
}
