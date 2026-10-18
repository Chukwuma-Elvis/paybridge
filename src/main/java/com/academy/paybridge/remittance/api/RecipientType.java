package com.academy.paybridge.remittance.api;

/** Where the naira goes. */
public enum RecipientType {
    /** A PayBridge account: paid by an internal credit. */
    PAYBRIDGE,
    /** An account at another bank: paid through the payment provider. */
    BANK
}
