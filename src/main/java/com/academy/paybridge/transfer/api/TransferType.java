package com.academy.paybridge.transfer.api;

public enum TransferType {
    /** Between two PayBridge accounts. */
    INTERNAL,
    /** To an account at another bank, through the gateway. */
    EXTERNAL
}
