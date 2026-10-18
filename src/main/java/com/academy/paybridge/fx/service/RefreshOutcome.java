package com.academy.paybridge.fx.service;

/** What happened when a rate was fetched from the source. */
public enum RefreshOutcome {
    /** Stored and now in force. */
    ACCEPTED,
    /** Moved too far from the previous rate; stored but NOT in force until an admin approves it. */
    HELD,
    /** The same held rate was already waiting for approval, so nothing new was stored. */
    HELD_ALREADY,
    /** Not believable (outside the limits); discarded. */
    REJECTED_IMPLAUSIBLE,
    /** Already too old when it arrived; discarded, so it cannot replace a fresher rate. */
    REJECTED_STALE,
    /** The source gave no answer; the last good rate stays in force until it goes stale. */
    SOURCE_DOWN
}
