package com.academy.paybridge.security.web;

import com.academy.paybridge.security.domain.ApiKeyRole;
import com.academy.paybridge.security.service.ApiKeyView;

import java.time.Instant;
import java.util.UUID;

/** A key as shown in lists: who it is for and whether it still works, never the key itself. */
public record ApiKeyResponse(UUID id, String name, String prefix, ApiKeyRole role, boolean active,
                             Instant createdAt, Instant revokedAt) {

    static ApiKeyResponse from(ApiKeyView view) {
        return new ApiKeyResponse(view.id(), view.name(), view.prefix(), view.role(), view.active(),
                view.createdAt(), view.revokedAt());
    }
}
