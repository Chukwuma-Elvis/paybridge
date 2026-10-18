package com.academy.paybridge.transfer.client;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaystackWebhookProviderTest {

    private static final String SECRET = "sk_test_unit_test_secret";

    private static PaystackWebhookProvider provider(String secret) {
        return new PaystackWebhookProvider(new WebhookProperties(65536, new WebhookProperties.Paystack(secret),
                new WebhookProperties.Monnify("", MonnifySignatureScheme.HMAC_SHA512)));
    }

    private final PaystackWebhookProvider provider = provider(SECRET);

    private static byte[] bytes(String json) {
        return json.getBytes(StandardCharsets.UTF_8);
    }

    private static String payload(String event, String dataFields) {
        return "{\"event\":\"" + event + "\",\"data\":{" + dataFields + "}}";
    }

    private ProviderEvent parse(String event, String dataFields) {
        return provider.parse(bytes(payload(event, dataFields)));
    }

    // ---- signature

    @Test
    void aCorrectSignatureIsAccepted() {
        byte[] body = bytes(payload("transfer.success", "\"reference\":\"pb-1\""));
        String signature = Hmac.hmacSha512Hex(SECRET, body);

        assertThat(provider.hasValidSignature(body, Map.of("x-paystack-signature", signature)::get)).isTrue();
    }

    @Test
    void aWrongMissingOrWrongKeySignatureIsRefused() {
        byte[] body = bytes(payload("transfer.success", "\"reference\":\"pb-1\""));

        assertThat(provider.hasValidSignature(body, name -> Hmac.hmacSha512Hex("another-secret", body))).isFalse();
        assertThat(provider.hasValidSignature(body, name -> "deadbeef")).isFalse();
        assertThat(provider.hasValidSignature(body, name -> null)).isFalse();
    }

    @Test
    void aSignatureForADifferentBodyIsRefused() {
        byte[] body = bytes(payload("transfer.success", "\"reference\":\"pb-1\""));
        byte[] tampered = bytes(payload("transfer.success", "\"reference\":\"pb-2\""));
        String signatureOfOriginal = Hmac.hmacSha512Hex(SECRET, body);

        assertThat(provider.hasValidSignature(tampered, name -> signatureOfOriginal)).isFalse();
    }

    @Test
    void theSignatureCoversTheExactBytesSoReformattingBreaksIt() {
        String compact = payload("transfer.success", "\"reference\":\"pb-1\"");
        String pretty = compact.replace(",", ",\n  ");
        String signatureOfCompact = Hmac.hmacSha512Hex(SECRET, bytes(compact));

        assertThat(provider.hasValidSignature(bytes(compact), name -> signatureOfCompact)).isTrue();
        assertThat(provider.hasValidSignature(bytes(pretty), name -> signatureOfCompact)).isFalse();
    }

    @Test
    void theHeaderIsLookedUpByItsPaystackName() {
        byte[] body = bytes(payload("transfer.success", "\"reference\":\"pb-1\""));
        String signature = Hmac.hmacSha512Hex(SECRET, body);
        List<String> asked = new ArrayList<>();

        provider.hasValidSignature(body, name -> {
            asked.add(name);
            return signature;
        });

        assertThat(asked).containsExactly("x-paystack-signature");
    }

    @Test
    void withoutASecretNothingIsAccepted() {
        PaystackWebhookProvider unconfigured = provider("");
        byte[] body = bytes(payload("transfer.success", "\"reference\":\"pb-1\""));

        assertThat(unconfigured.isConfigured()).isFalse();
        assertThat(unconfigured.hasValidSignature(body, name -> Hmac.hmacSha512Hex("", body))).isFalse();
        assertThat(provider.isConfigured()).isTrue();
        assertThat(provider.name()).isEqualTo("paystack");
    }

    // ---- events

    @Test
    void successIsRead() {
        ProviderEvent event = parse("transfer.success",
                "\"id\":37272792,\"reference\":\"pb-abc\",\"amount\":200000,\"currency\":\"NGN\",\"transfer_code\":\"TRF_xyz\",\"reason\":\"rent\"");

        assertThat(event.outcome()).isEqualTo(GatewayOutcome.SUCCESS);
        assertThat(event.reference()).isEqualTo("pb-abc");
        assertThat(event.amountMinor()).isEqualTo(200_000L);
        assertThat(event.currency()).isEqualTo("NGN");
        assertThat(event.providerReference()).isEqualTo("TRF_xyz");
        assertThat(event.eventType()).isEqualTo("transfer.success");
        assertThat(event.isActionable()).isTrue();
    }

    @Test
    void failureCarriesTheProvidersReason() {
        ProviderEvent event = parse("transfer.failed", "\"id\":1,\"reference\":\"pb-abc\",\"amount\":100,\"complete_message\":\"Insufficient funds\"");

        assertThat(event.outcome()).isEqualTo(GatewayOutcome.FAILED);
        assertThat(event.message()).isEqualTo("Insufficient funds");
    }

    @Test
    void failureWithoutAReasonGetsAPlainOne() {
        ProviderEvent event = parse("transfer.failed", "\"id\":1,\"reference\":\"pb-abc\"");

        assertThat(event.message()).isEqualTo("The transfer failed at the payment provider");
    }

    @Test
    void reversalIsRead() {
        ProviderEvent event = parse("transfer.reversed", "\"id\":1,\"reference\":\"pb-abc\",\"amount\":100");

        assertThat(event.outcome()).isEqualTo(GatewayOutcome.REVERSED);
        assertThat(event.message()).isEqualTo("The transfer was reversed by the payment provider");
    }

    @Test
    void theEventIdCombinesTheEventNameAndTheProvidersId() {
        assertThat(parse("transfer.success", "\"id\":37272792,\"reference\":\"pb-a\"").eventId()).isEqualTo("transfer.success:37272792");
        assertThat(parse("transfer.reversed", "\"id\":37272792,\"reference\":\"pb-a\"").eventId()).isEqualTo("transfer.reversed:37272792");
    }

    @Test
    void withoutAnIdTheEventIdIsAFingerprintOfTheBody() {
        byte[] body = bytes(payload("transfer.success", "\"reference\":\"pb-a\""));

        ProviderEvent first = provider.parse(body);
        ProviderEvent again = provider.parse(body);
        ProviderEvent different = provider.parse(bytes(payload("transfer.success", "\"reference\":\"pb-b\"")));

        assertThat(first.eventId()).startsWith("sha256:").isEqualTo(again.eventId()).isNotEqualTo(different.eventId());
    }

    @Test
    void otherEventsAreNotActionable() {
        ProviderEvent event = provider.parse(bytes("{\"event\":\"charge.success\",\"data\":{\"id\":9,\"reference\":\"x\",\"amount\":500}}"));

        assertThat(event.outcome()).isNull();
        assertThat(event.isActionable()).isFalse();
        assertThat(event.amountMinor()).isNull();
    }

    @Test
    void aMissingReferenceIsAllowedButReportedAsNull() {
        assertThat(parse("transfer.success", "\"id\":1,\"amount\":100").reference()).isNull();
    }

    @Test
    void anAmountThatIsNotAWholeNumberIsMalformed() {
        assertThatThrownBy(() -> parse("transfer.success", "\"id\":1,\"reference\":\"pb-a\",\"amount\":10.5"))
                .isInstanceOf(MalformedWebhookException.class);
        assertThatThrownBy(() -> parse("transfer.success", "\"id\":1,\"reference\":\"pb-a\",\"amount\":\"lots\""))
                .isInstanceOf(MalformedWebhookException.class);
    }

    @Test
    void aTextAmountOfWholeKoboIsAccepted() {
        assertThat(parse("transfer.success", "\"id\":1,\"reference\":\"pb-a\",\"amount\":\"5000\"").amountMinor()).isEqualTo(5_000L);
    }

    @Test
    void notJsonIsMalformed() {
        assertThatThrownBy(() -> provider.parse(bytes("this is not json"))).isInstanceOf(MalformedWebhookException.class);
        assertThatThrownBy(() -> provider.parse(bytes(""))).isInstanceOf(MalformedWebhookException.class);
    }

    @Test
    void jsonThatIsNotAnObjectIsMalformed() {
        assertThatThrownBy(() -> provider.parse(bytes("[1,2,3]"))).isInstanceOf(MalformedWebhookException.class);
        assertThatThrownBy(() -> provider.parse(bytes("\"text\""))).isInstanceOf(MalformedWebhookException.class);
    }

    @Test
    void aBodyWithNoEventNameIsMalformed() {
        assertThatThrownBy(() -> provider.parse(bytes("{\"data\":{\"reference\":\"x\"}}")))
                .isInstanceOf(MalformedWebhookException.class)
                .hasMessageContaining("event");
    }

    @Test
    void theProvidersReasonIsCleanedAndShortened() {
        String longReason = "x".repeat(500);
        ProviderEvent event = parse("transfer.failed", "\"id\":1,\"reference\":\"pb-a\",\"reason\":\"line1\\nline2 " + longReason + "\"");

        assertThat(event.message()).doesNotContain("\n").hasSizeLessThanOrEqualTo(200).startsWith("line1 line2");
    }
}
