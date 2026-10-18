package com.academy.paybridge.transfer.client;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * The signature maths that webhook providers use. A webhook signature is a "wax seal": the provider mixes the message with a
 * secret only the two of us know, and sends the result alongside. We redo the sum and compare. A forger who does not know the
 * secret cannot produce a matching seal.
 */
public final class Hmac {

    private Hmac() {
    }

    /** HMAC-SHA512 of the message, keyed with the secret, as lower-case hex. */
    public static String hmacSha512Hex(String secret, byte[] message) {
        try {
            Mac mac = Mac.getInstance("HmacSHA512");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA512"));
            return HexFormat.of().formatHex(mac.doFinal(message));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA512 is required on every Java platform", e);
        }
    }

    /** Plain SHA-512 of (secret followed by message), as lower-case hex. One of the two schemes Monnify's documentation describes. */
    public static String sha512OfSecretThenMessageHex(String secret, byte[] message) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-512");
            digest.update(secret.getBytes(StandardCharsets.UTF_8));
            digest.update(message);
            return HexFormat.of().formatHex(digest.digest());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("SHA-512 is required on every Java platform", e);
        }
    }

    /** SHA-256 of the message as lower-case hex (a fingerprint we can store instead of the message). */
    public static String sha256Hex(byte[] message) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(message));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("SHA-256 is required on every Java platform", e);
        }
    }

    /**
     * Does the text the caller sent equal the signature we worked out?
     * <p>
     * Compared in CONSTANT TIME: an ordinary comparison stops at the first wrong character, and how long it took could leak
     * how much of a guess was right. Upper or lower case hex is accepted; anything that is not valid hex is simply "no".
     */
    public static boolean signatureMatches(String expectedHex, String presented) {
        if (expectedHex == null || presented == null) {
            return false;
        }
        String candidate = presented.trim();
        if (candidate.length() != expectedHex.length() || !isHex(candidate)) {
            return false;
        }
        byte[] expected = HexFormat.of().parseHex(expectedHex);
        byte[] actual = HexFormat.of().parseHex(candidate);
        return MessageDigest.isEqual(expected, actual);
    }

    private static boolean isHex(String text) {
        for (int i = 0; i < text.length(); i++) {
            if (Character.digit(text.charAt(i), 16) < 0) {
                return false;
            }
        }
        return true;
    }
}
