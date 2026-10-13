package com.academy.paybridge.security.service;

import com.academy.paybridge.security.domain.ApiKeyRole;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Solves the chicken-and-egg problem: creating API keys needs an admin key, but the first admin key has to come from
 * somewhere. The operator supplies it through the PAYBRIDGE_ADMIN_API_KEY environment variable; at startup we register
 * its hash (the key itself is never stored or logged).
 * <p>
 * If you later revoke the bootstrap key it stays revoked: the hash is already on record, so it is not re-added.
 * To rotate, set a new value for the variable.
 */
@Component
class AdminKeyBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminKeyBootstrap.class);
    private static final int MINIMUM_BOOTSTRAP_KEY_LENGTH = 32;

    private final SecurityProperties properties;
    private final ApiKeyService apiKeyService;

    AdminKeyBootstrap(SecurityProperties properties, ApiKeyService apiKeyService) {
        this.properties = properties;
        this.apiKeyService = apiKeyService;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!properties.enabled()) {
            log.warn("API-key security is DISABLED (paybridge.security.enabled=false): every endpoint is open. "
                    + "Use this on a private developer machine only.");
            return;
        }

        String bootstrapKey = properties.bootstrapAdminKey();
        if (bootstrapKey != null && !bootstrapKey.isBlank()) {
            registerBootstrapKey(bootstrapKey);
        } else if (!apiKeyService.hasActiveAdminKey()) {
            log.warn("No admin API key exists, so every protected endpoint will answer 401 and no key can be created. "
                    + "Set the PAYBRIDGE_ADMIN_API_KEY environment variable (at least {} characters of letters, "
                    + "digits, '_' or '-') and restart.", MINIMUM_BOOTSTRAP_KEY_LENGTH);
        }
    }

    private void registerBootstrapKey(String bootstrapKey) {
        if (bootstrapKey.length() < MINIMUM_BOOTSTRAP_KEY_LENGTH || !ApiKeyService.hasValidShape(bootstrapKey)) {
            // Refuse to start rather than run with a guessable admin key. The message never repeats the key.
            throw new IllegalStateException("PAYBRIDGE_ADMIN_API_KEY must be at least " + MINIMUM_BOOTSTRAP_KEY_LENGTH
                    + " characters long and use only letters, digits, '_' and '-'.");
        }
        boolean added = apiKeyService.ensureKey(bootstrapKey, "bootstrap-admin", ApiKeyRole.ADMIN);
        log.info(added ? "Registered the bootstrap admin API key." : "The bootstrap admin API key is already registered.");
    }
}
