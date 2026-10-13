package com.academy.paybridge.security.service;

import com.academy.paybridge.security.domain.ApiKey;
import com.academy.paybridge.security.domain.ApiKeyRole;
import com.academy.paybridge.security.repository.ApiKeyRepository;
import com.academy.paybridge.shared.exception.ResourceNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Clock;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Creates, checks and revokes API keys.
 * <p>
 * The life of a key: it is generated from 32 random bytes, shown to the caller ONCE, and only its hash is kept.
 * To check a request we hash the key it carries and look the hash up. The key itself is never stored or logged.
 */
@Service
@Transactional
public class ApiKeyService {

    /** Anything that does not look like a key is turned away without touching the database. */
    private static final Pattern KEY_FORMAT = Pattern.compile("^[A-Za-z0-9_-]{20,200}$");

    private static final String KEY_PREFIX = "pb_";
    private static final int PREFIX_LENGTH_SHOWN = 11;
    private static final String OPERATOR_KEY_LABEL = "(operator)";

    private final ApiKeyRepository apiKeyRepository;
    private final Clock clock;
    private final SecureRandom secureRandom = new SecureRandom();

    public ApiKeyService(ApiKeyRepository apiKeyRepository, Clock clock) {
        this.apiKeyRepository = apiKeyRepository;
        this.clock = clock;
    }

    /** Makes a brand-new key. The returned plaintext is the only copy there will ever be. */
    public CreatedApiKey create(String name, ApiKeyRole role) {
        String plaintext = generateKey();
        ApiKey saved = apiKeyRepository.save(newRecord(name, role, plaintext));
        return new CreatedApiKey(ApiKeyView.from(saved), plaintext);
    }

    /**
     * Registers a key chosen by the operator (the bootstrap admin key), unless it is already registered.
     *
     * @return true if it was added, false if it was already there
     */
    public boolean ensureKey(String plaintext, String name, ApiKeyRole role) {
        if (apiKeyRepository.existsByKeyHash(ApiKeyHasher.sha256Hex(plaintext))) {
            return false;
        }
        // An operator-chosen key is not random, so none of its characters are shown: the prefix is a fixed label.
        apiKeyRepository.save(new ApiKey(name, OPERATOR_KEY_LABEL, ApiKeyHasher.sha256Hex(plaintext), role));
        return true;
    }

    /** Who owns this key? Empty when it is malformed, unknown or revoked. */
    @Transactional(readOnly = true)
    public Optional<AuthenticatedClient> authenticate(String presentedKey) {
        if (presentedKey == null || !KEY_FORMAT.matcher(presentedKey).matches()) {
            return Optional.empty();
        }
        return apiKeyRepository.findByKeyHash(ApiKeyHasher.sha256Hex(presentedKey))
                .filter(ApiKey::isActive)
                .map(key -> new AuthenticatedClient(key.getId(), key.getName(), key.getRole()));
    }

    /** Switches a key off for good. Safe to repeat. */
    public ApiKeyView revoke(UUID id) {
        ApiKey key = apiKeyRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("API key", id));
        key.revoke(clock.instant());
        return ApiKeyView.from(key);
    }

    @Transactional(readOnly = true)
    public List<ApiKeyView> list() {
        return apiKeyRepository.findAllByOrderByCreatedAtDesc().stream().map(ApiKeyView::from).toList();
    }

    @Transactional(readOnly = true)
    public boolean hasActiveAdminKey() {
        return apiKeyRepository.existsByRoleAndRevokedAtIsNull(ApiKeyRole.ADMIN);
    }

    /** True when the text has the shape of a key (used to refuse a weak bootstrap key at startup). */
    public static boolean hasValidShape(String candidate) {
        return candidate != null && KEY_FORMAT.matcher(candidate).matches();
    }

    private ApiKey newRecord(String name, ApiKeyRole role, String plaintext) {
        String prefix = plaintext.substring(0, Math.min(PREFIX_LENGTH_SHOWN, plaintext.length()));
        return new ApiKey(name, prefix, ApiKeyHasher.sha256Hex(plaintext), role);
    }

    private String generateKey() {
        byte[] randomBytes = new byte[32];
        secureRandom.nextBytes(randomBytes);
        return KEY_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);
    }
}
