package com.academy.paybridge.security.domain;

/**
 * What a key is allowed to do.
 * <ul>
 *   <li>{@link #CLIENT}: the normal business endpoints (customers, accounts, transfers, and so on).</li>
 *   <li>{@link #ADMIN}: everything a client can do, plus managing API keys, the dev funding endpoint and the
 *       operational endpoints such as metrics.</li>
 * </ul>
 */
public enum ApiKeyRole {
    CLIENT,
    ADMIN
}
