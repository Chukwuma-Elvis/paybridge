package com.academy.paybridge.ledger.api;

public enum EntryType {
    /** Test money moving from the funding source into a customer account (dev only). */
    FUNDING,
    /** A transfer between two PayBridge accounts. */
    TRANSFER,
    /** External transfer, step 1: the customer's money (amount plus fee) is parked in suspense. */
    EXTERNAL_HOLD,
    /** External transfer, step 2: suspense is paid out to Paystack and the fee is earned. */
    EXTERNAL_SETTLEMENT,
    /** Undoes an earlier entry. */
    REVERSAL,
    /** Remittance: the partner's USD arrives and is held in the remittance clearing account. */
    REMITTANCE_FUNDING,
    /** Currency conversion, the USD half: USD leaves clearing and enters the house's USD position. */
    FX_CONVERSION_USD,
    /** Currency conversion, the NGN half: NGN leaves the house's NGN position and enters clearing. */
    FX_CONVERSION_NGN,
    /** Remittance: naira leaves clearing and is paid to the recipient. */
    REMITTANCE_PAYOUT,
    /** Settlement: a net amount is paid between PayBridge and a partner, clearing the partner's account. */
    SETTLEMENT_PAYMENT,
    /** Settlement: a correcting entry that resolves a reconciliation break. */
    SETTLEMENT_ADJUSTMENT
}
