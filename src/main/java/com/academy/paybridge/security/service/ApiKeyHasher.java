package com.academy.paybridge.security.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Turns a key into the fingerprint we store. SHA-256 is one-way: you can go from a key to its hash, but not back.
 * <p>
 * A fast hash is right here because our keys are 256 bits of pure randomness, so there is nothing to guess.
 * (Human-chosen passwords need a deliberately SLOW hash such as bcrypt, because people choose guessable ones.)
 */
public final class ApiKeyHasher {

    private ApiKeyHasher() {
    }

    public static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required on every Java platform", e);
        }
    }
}
