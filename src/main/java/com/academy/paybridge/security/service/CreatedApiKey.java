package com.academy.paybridge.security.service;

/**
 * The one moment a key exists in readable form: right after it is created.
 *
 * @param key       the key's details
 * @param plaintext the actual key. It is handed over once, here, and cannot be recovered afterwards
 */
public record CreatedApiKey(ApiKeyView key, String plaintext) {
}
