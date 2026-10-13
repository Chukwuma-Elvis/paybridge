package com.academy.paybridge.security.web;

import com.academy.paybridge.security.domain.ApiKeyRole;
import com.academy.paybridge.security.service.CreatedApiKey;

import java.time.Instant;
import java.util.UUID;

/** The reply to creating a key: the only time the key itself is ever shown. */
public record CreatedApiKeyResponse(UUID id, String name, String prefix, ApiKeyRole role, Instant createdAt,
                                    String apiKey, String notice) {

    static CreatedApiKeyResponse from(CreatedApiKey created) {
        return new CreatedApiKeyResponse(created.key().id(), created.key().name(), created.key().prefix(),
                created.key().role(), created.key().createdAt(), created.plaintext(),
                "Copy this key now. It is not stored and cannot be shown again.");
    }
}
