package com.academy.paybridge.transfer.service;

import com.academy.paybridge.shared.exception.ErrorCode;
import com.academy.paybridge.shared.exception.PayBridgeException;
import com.academy.paybridge.transfer.domain.IdempotencyKey;
import com.academy.paybridge.transfer.repository.IdempotencyKeyRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

/**
 * Makes "the client retried" harmless. Must run inside the transfer's own transaction, so the key and the
 * transfer it produced are saved together or not at all.
 */
@Service
@Transactional(propagation = Propagation.MANDATORY)
public class IdempotencyService {

    private final IdempotencyKeyRepository repository;

    public IdempotencyService(IdempotencyKeyRepository repository) {
        this.repository = repository;
    }

    /**
     * @return empty if this is a brand-new request (go ahead and do the work); otherwise the id of the transfer
     *         this key already produced (return that instead of doing the work again)
     * @throws PayBridgeException {@code IDEMPOTENCY_KEY_REUSED} if the key was used for a different request
     */
    public Optional<UUID> begin(String scope, String key, String requestHash) {
        if (repository.claim(scope, key, requestHash) == 1) {
            return Optional.empty();
        }

        IdempotencyKey existing = repository.find(scope, key)
                .orElseThrow(() -> new IllegalStateException("Idempotency key vanished: " + key));
        if (!existing.getRequestHash().equals(requestHash)) {
            throw new PayBridgeException(ErrorCode.IDEMPOTENCY_KEY_REUSED,
                    "The Idempotency-Key " + key + " was already used for a different request");
        }
        if (existing.getTransferId() == null) {
            throw new PayBridgeException(ErrorCode.IDEMPOTENCY_KEY_IN_PROGRESS,
                    "A request with Idempotency-Key " + key + " is still being processed");
        }
        return Optional.of(existing.getTransferId());
    }

    /** Records which transfer the key produced. */
    public void complete(String scope, String key, UUID transferId) {
        repository.find(scope, key)
                .orElseThrow(() -> new IllegalStateException("Idempotency key not claimed: " + key))
                .completeWith(transferId);
    }

    /** A fingerprint of the request's meaningful fields. Two requests with the same fingerprint are "the same request". */
    public static String fingerprint(Object... parts) {
        StringBuilder canonical = new StringBuilder();
        for (Object part : parts) {
            canonical.append(part == null ? "" : part).append('\u001f'); // unit separator: cannot appear in the data
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always available", e);
        }
    }
}
