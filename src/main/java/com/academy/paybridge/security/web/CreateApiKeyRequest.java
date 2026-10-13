package com.academy.paybridge.security.web;

import com.academy.paybridge.security.domain.ApiKeyRole;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateApiKeyRequest(
        @NotBlank(message = "must not be blank")
        @Size(max = 100, message = "must be at most 100 characters")
        @Pattern(regexp = "^[A-Za-z0-9 ._-]*$", message = "may only contain letters, digits, spaces, '.', '_' and '-'")
        String name,

        @NotNull(message = "must be CLIENT or ADMIN")
        ApiKeyRole role) {
}
