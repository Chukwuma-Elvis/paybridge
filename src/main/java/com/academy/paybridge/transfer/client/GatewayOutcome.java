package com.academy.paybridge.transfer.client;

/** What the payment provider says happened to a transfer. */
public enum GatewayOutcome {
    /** The money reached the recipient's bank. */
    SUCCESS,
    /** The provider definitively refused or failed the transfer. Nothing was sent. */
    FAILED,
    /** The provider accepted it and is still working. The final answer comes later. */
    PENDING,
    /** It had succeeded, then the provider took it back. */
    REVERSED,
    /** The provider has no record of this reference: it never received the request. */
    NOT_FOUND
}
