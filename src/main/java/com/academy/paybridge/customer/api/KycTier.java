package com.academy.paybridge.customer.api;

/**
 * How thoroughly a customer's identity has been verified. Other modules (compliance, transfer)
 * read this to decide what the customer is allowed to do.
 */
public enum KycTier {
    UNVERIFIED,
    BASIC,
    FULL
}
