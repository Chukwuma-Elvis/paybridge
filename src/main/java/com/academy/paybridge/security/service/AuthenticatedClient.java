package com.academy.paybridge.security.service;

import com.academy.paybridge.security.domain.ApiKeyRole;

import java.util.UUID;

/**
 * Who is calling: the answer to "this request carried a valid key, so whose is it?". It never contains the key.
 *
 * @param keyId the id of the API key record
 * @param name  the human label given to the key, for example "mobile-app"
 * @param role  what the key may do
 */
public record AuthenticatedClient(UUID keyId, String name, ApiKeyRole role) {

    public boolean isAdmin() {
        return role == ApiKeyRole.ADMIN;
    }
}
