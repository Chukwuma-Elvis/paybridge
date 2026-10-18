package com.academy.paybridge.fx.domain;

/** Whether a stored rate may be used. */
public enum RateStatus {
    /** In force: quotes may be based on it. */
    ACTIVE,
    /** Moved too far from the previous rate to trust automatically. Not used until an admin approves it. */
    HELD
}
