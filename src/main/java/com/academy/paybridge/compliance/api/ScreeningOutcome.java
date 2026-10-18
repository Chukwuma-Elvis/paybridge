package com.academy.paybridge.compliance.api;

/** What screening decided. There is no "probably fine": an ambiguous hit is REVIEW, never CLEAR. */
public enum ScreeningOutcome {
    /** No meaningful match: proceed. */
    CLEAR,
    /** Possible match: a person must decide before anything moves. */
    REVIEW,
    /** Strong, corroborated match: stop and escalate. */
    BLOCK
}
