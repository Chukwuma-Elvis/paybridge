package com.academy.paybridge.security.service;

import com.academy.paybridge.security.domain.ApiKey;
import com.academy.paybridge.security.domain.ApiKeyRole;

import java.time.Instant;
import java.util.UUID;

/** What may be shown about a key. There is no key and no hash in here: neither can ever be read back. */
public record ApiKeyView(UUID id, String name, String prefix, ApiKeyRole role, boolean active,
                         Instant createdAt, Instant revokedAt) {

    static ApiKeyView from(ApiKey key) {
        return new ApiKeyView(key.getId(), key.getName(), key.getKeyPrefix(), key.getRole(), key.isActive(),
                key.getCreatedAt(), key.getRevokedAt());
    }
}
