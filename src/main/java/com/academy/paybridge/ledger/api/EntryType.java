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
    REVERSAL
}
