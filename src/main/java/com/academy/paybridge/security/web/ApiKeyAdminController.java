package com.academy.paybridge.security.web;

import com.academy.paybridge.security.service.ApiKeyService;
import com.academy.paybridge.security.service.CreatedApiKey;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;
import java.util.UUID;

/**
 * Managing API keys. Everything under {@code /api/admin} needs an ADMIN key; {@link ApiKeyAuthFilter} enforces that
 * before a request ever gets here.
 */
@RestController
@RequestMapping("/api/admin/api-keys")
public class ApiKeyAdminController {

    private final ApiKeyService apiKeyService;

    public ApiKeyAdminController(ApiKeyService apiKeyService) {
        this.apiKeyService = apiKeyService;
    }

    @PostMapping
    public ResponseEntity<CreatedApiKeyResponse> create(@Valid @RequestBody CreateApiKeyRequest request) {
        CreatedApiKey created = apiKeyService.create(request.name().trim(), request.role());
        return ResponseEntity
                .created(URI.create("/api/admin/api-keys/" + created.key().id()))
                .cacheControl(CacheControl.noStore())
                .body(CreatedApiKeyResponse.from(created));
    }

    @GetMapping
    public List<ApiKeyResponse> list() {
        return apiKeyService.list().stream().map(ApiKeyResponse::from).toList();
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> revoke(@PathVariable UUID id) {
        apiKeyService.revoke(id);
        return ResponseEntity.noContent().build();
    }
}
