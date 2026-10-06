package com.academy.paybridge.account.api;

public enum AccountType {
    /** An account that belongs to a customer. Its balance can never go below zero. */
    CUSTOMER,
    /** One of the bank's own internal accounts (suspense, fees, ...). */
    SYSTEM
}
