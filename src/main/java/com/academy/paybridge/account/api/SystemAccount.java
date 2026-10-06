package com.academy.paybridge.account.api;

/**
 * The bank's internal accounts. The names match the {@code code} column seeded by the migration.
 */
public enum SystemAccount {
    /** Where test money comes from. Only used by the dev funding endpoint. */
    FUNDING_SOURCE,
    /** Holds money "in transit" while an external transfer is in flight. */
    SUSPENSE,
    /** Money we have handed to Paystack. */
    PAYSTACK_SETTLEMENT,
    /** Fee income. */
    FEES
}
