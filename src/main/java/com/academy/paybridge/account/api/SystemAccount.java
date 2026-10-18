package com.academy.paybridge.account.api;

/**
 * The bank's internal accounts. The names match the {@code code} column seeded by the migrations.
 * <p>
 * Each account holds ONE currency. Accounts that are created at run time, such as the settlement account for each corridor partner,
 * are not listed here: they are found by code through {@link AccountApi#systemAccountIdByCode(String)}.
 */
public enum SystemAccount {
    /** Where test money comes from. Only used by the dev funding endpoint. (NGN) */
    FUNDING_SOURCE,
    /** Holds money "in transit" while an external transfer is in flight. (NGN) */
    SUSPENSE,
    /** Money we have handed to Paystack. (NGN) */
    PAYSTACK_SETTLEMENT,
    /** Fee income. (NGN) */
    FEES,
    /** The house's net holding of dollars after conversions: its open exposure. (USD) */
    FX_POSITION_USD,
    /** The house's net holding of naira after conversions: its open exposure. (NGN) */
    FX_POSITION_NGN,
    /** Remittance dollars waiting to be converted. (USD) */
    REMITTANCE_CLEARING_USD,
    /** Remittance naira waiting to be paid to the recipient. (NGN) */
    REMITTANCE_CLEARING_NGN,
    /** Correcting entries that resolve settlement breaks. (NGN) */
    SETTLEMENT_ADJUSTMENT_NGN,
    /** Correcting entries that resolve settlement breaks. (USD) */
    SETTLEMENT_ADJUSTMENT_USD
}
