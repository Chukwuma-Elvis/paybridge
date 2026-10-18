package com.academy.paybridge.transfer.client;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class HmacTest {

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void matchesTheOfficialHmacSha512TestVector() {
        // RFC 4231, test case 2: key "Jefe", message "what do ya want for nothing?"
        assertThat(Hmac.hmacSha512Hex("Jefe", bytes("what do ya want for nothing?")))
                .isEqualTo("164b7a7bfcf819e2e395fbe73b56e0a387bd64222e831fd610270cd7ea250554"
                        + "9758bf75c05a994a6d034f65f8f0e6fdcaeab1a34d4a6b4b636e070a38bce737");
    }

    @Test
    void theSameInputAlwaysGivesTheSameSignature() {
        assertThat(Hmac.hmacSha512Hex("secret", bytes("body"))).isEqualTo(Hmac.hmacSha512Hex("secret", bytes("body")));
    }

    @Test
    void aDifferentSecretOrBodyGivesADifferentSignature() {
        String original = Hmac.hmacSha512Hex("secret", bytes("body"));

        assertThat(Hmac.hmacSha512Hex("other", bytes("body"))).isNotEqualTo(original);
        assertThat(Hmac.hmacSha512Hex("secret", bytes("body "))).isNotEqualTo(original);
    }

    @Test
    void aSignatureIs128HexCharacters() {
        assertThat(Hmac.hmacSha512Hex("secret", bytes("body"))).hasSize(128).matches("[0-9a-f]+");
    }

    @Test
    void sha256IsTheKnownFingerprint() {
        assertThat(Hmac.sha256Hex(bytes("abc")))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }

    @Test
    void secretThenMessageHashDiffersFromTheHmac() {
        assertThat(Hmac.sha512OfSecretThenMessageHex("secret", bytes("body")))
                .hasSize(128)
                .isNotEqualTo(Hmac.hmacSha512Hex("secret", bytes("body")));
    }

    @Test
    void aMatchingSignatureIsAccepted() {
        String expected = Hmac.hmacSha512Hex("secret", bytes("body"));

        assertThat(Hmac.signatureMatches(expected, expected)).isTrue();
    }

    @Test
    void upperCaseHexIsAcceptedAndSurroundingSpacesAreIgnored() {
        String expected = Hmac.hmacSha512Hex("secret", bytes("body"));

        assertThat(Hmac.signatureMatches(expected, expected.toUpperCase())).isTrue();
        assertThat(Hmac.signatureMatches(expected, "  " + expected + " ")).isTrue();
    }

    @Test
    void oneChangedCharacterIsRefused() {
        String expected = Hmac.hmacSha512Hex("secret", bytes("body"));
        String altered = (expected.charAt(0) == 'a' ? "b" : "a") + expected.substring(1);

        assertThat(Hmac.signatureMatches(expected, altered)).isFalse();
    }

    @Test
    void theWrongLengthIsRefused() {
        String expected = Hmac.hmacSha512Hex("secret", bytes("body"));

        assertThat(Hmac.signatureMatches(expected, expected.substring(1))).isFalse();
        assertThat(Hmac.signatureMatches(expected, expected + "00")).isFalse();
        assertThat(Hmac.signatureMatches(expected, "")).isFalse();
    }

    @Test
    void nonHexTextIsRefusedNotAnError() {
        String expected = Hmac.hmacSha512Hex("secret", bytes("body"));
        String notHex = "z".repeat(expected.length());

        assertThat(Hmac.signatureMatches(expected, notHex)).isFalse();
    }

    @Test
    void nullsAreRefused() {
        assertThat(Hmac.signatureMatches(null, "abc")).isFalse();
        assertThat(Hmac.signatureMatches("abc", null)).isFalse();
    }
}
