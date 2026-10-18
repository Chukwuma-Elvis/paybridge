package com.academy.paybridge.transfer.client;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MonnifyWebhookProviderTest {

    private static final String SECRET = "monnify_unit_test_secret";

    private static MonnifyWebhookProvider provider(String secret, MonnifySignatureScheme scheme) {
        return new MonnifyWebhookProvider(new WebhookProperties(65536, new WebhookProperties.Paystack(""),
                new WebhookProperties.Monnify(secret, scheme)));
    }

    private final MonnifyWebhookProvider provider = provider(SECRET, MonnifySignatureScheme.HMAC_SHA512);

    private static byte[] bytes(String json) {
        return json.getBytes(StandardCharsets.UTF_8);
    }

    private static String payload(String eventType, String dataFields) {
        return "{\"eventType\":\"" + eventType + "\",\"eventData\":{" + dataFields + "}}";
    }

    private ProviderEvent parse(String eventType, String dataFields) {
        return provider.parse(bytes(payload(eventType, dataFields)));
    }

    // ---- signature

    @Test
    void theHmacSchemeAcceptsACorrectSignatureAndRefusesTheOtherRecipe() {
        byte[] body = bytes(payload("SUCCESSFUL_DISBURSEMENT", "\"reference\":\"pb-1\""));
        String hmac = Hmac.hmacSha512Hex(SECRET, body);
        String prefix = Hmac.sha512OfSecretThenMessageHex(SECRET, body);

        assertThat(provider.hasValidSignature(body, name -> hmac)).isTrue();
        assertThat(provider.hasValidSignature(body, name -> prefix)).isFalse();
    }

    @Test
    void theSecretPrefixSchemeAcceptsItsOwnRecipeOnly() {
        MonnifyWebhookProvider prefixScheme = provider(SECRET, MonnifySignatureScheme.SHA512_SECRET_PREFIX);
        byte[] body = bytes(payload("SUCCESSFUL_DISBURSEMENT", "\"reference\":\"pb-1\""));

        assertThat(prefixScheme.hasValidSignature(body, name -> Hmac.sha512OfSecretThenMessageHex(SECRET, body))).isTrue();
        assertThat(prefixScheme.hasValidSignature(body, name -> Hmac.hmacSha512Hex(SECRET, body))).isFalse();
    }

    @Test
    void aWrongOrMissingSignatureIsRefused() {
        byte[] body = bytes(payload("SUCCESSFUL_DISBURSEMENT", "\"reference\":\"pb-1\""));

        assertThat(provider.hasValidSignature(body, name -> "00")).isFalse();
        assertThat(provider.hasValidSignature(body, name -> null)).isFalse();
        assertThat(provider.hasValidSignature(body, name -> Hmac.hmacSha512Hex("wrong", body))).isFalse();
    }

    @Test
    void theHeaderIsLookedUpByItsMonnifyName() {
        byte[] body = bytes(payload("SUCCESSFUL_DISBURSEMENT", "\"reference\":\"pb-1\""));
        List<String> asked = new ArrayList<>();

        provider.hasValidSignature(body, name -> {
            asked.add(name);
            return null;
        });

        assertThat(asked).containsExactly("monnify-signature");
    }

    @Test
    void withoutASecretNothingIsAccepted() {
        MonnifyWebhookProvider unconfigured = provider("", MonnifySignatureScheme.HMAC_SHA512);
        byte[] body = bytes(payload("SUCCESSFUL_DISBURSEMENT", "\"reference\":\"pb-1\""));

        assertThat(unconfigured.isConfigured()).isFalse();
        assertThat(unconfigured.hasValidSignature(body, name -> Hmac.hmacSha512Hex("", body))).isFalse();
        assertThat(provider.name()).isEqualTo("monnify");
    }

    // ---- events

    @Test
    void successIsReadAndNairaBecomeKobo() {
        ProviderEvent event = parse("SUCCESSFUL_DISBURSEMENT",
                "\"reference\":\"pb-abc\",\"transactionReference\":\"MFDS|2021|0001\",\"amount\":2000.50,\"currency\":\"NGN\"");

        assertThat(event.outcome()).isEqualTo(GatewayOutcome.SUCCESS);
        assertThat(event.reference()).isEqualTo("pb-abc");
        assertThat(event.amountMinor()).isEqualTo(200_050L);
        assertThat(event.currency()).isEqualTo("NGN");
        assertThat(event.providerReference()).isEqualTo("MFDS|2021|0001");
        assertThat(event.eventId()).isEqualTo("SUCCESSFUL_DISBURSEMENT:MFDS|2021|0001");
    }

    @Test
    void failureAndReversalAreRead() {
        ProviderEvent failed = parse("FAILED_DISBURSEMENT",
                "\"reference\":\"pb-a\",\"transactionReference\":\"T1\",\"message\":\"Account blocked\"");

        assertThat(failed.outcome()).isEqualTo(GatewayOutcome.FAILED);
        assertThat(failed.message()).isEqualTo("Account blocked");
        assertThat(parse("REVERSED_DISBURSEMENT", "\"reference\":\"pb-a\",\"transactionReference\":\"T2\"").outcome())
                .isEqualTo(GatewayOutcome.REVERSED);
    }

    @Test
    void amountsAreConvertedExactlyNeverThroughFloatingPoint() {
        String head = "\"reference\":\"x\",\"transactionReference\":\"T\",\"amount\":";
        assertThat(parse("SUCCESSFUL_DISBURSEMENT", head + "0.10").amountMinor()).isEqualTo(10L);
        assertThat(parse("SUCCESSFUL_DISBURSEMENT", head + "19.99").amountMinor()).isEqualTo(1_999L);
        assertThat(parse("SUCCESSFUL_DISBURSEMENT", head + "2000").amountMinor()).isEqualTo(200_000L);
        assertThat(parse("SUCCESSFUL_DISBURSEMENT", head + "\"150.5\"").amountMinor()).isEqualTo(15_050L);
    }

    @Test
    void anAmountWithMoreThanTwoDecimalPlacesIsRefusedNotRounded() {
        assertThatThrownBy(() -> parse("SUCCESSFUL_DISBURSEMENT", "\"reference\":\"x\",\"transactionReference\":\"T\",\"amount\":10.005"))
                .isInstanceOf(MalformedWebhookException.class);
    }

    @Test
    void aNegativeOrNonNumericAmountIsRefused() {
        assertThatThrownBy(() -> parse("SUCCESSFUL_DISBURSEMENT", "\"reference\":\"x\",\"transactionReference\":\"T\",\"amount\":-5"))
                .isInstanceOf(MalformedWebhookException.class);
        assertThatThrownBy(() -> parse("SUCCESSFUL_DISBURSEMENT", "\"reference\":\"x\",\"transactionReference\":\"T\",\"amount\":\"abc\""))
                .isInstanceOf(MalformedWebhookException.class);
    }

    @Test
    void withoutATransactionReferenceTheEventIdIsAFingerprint() {
        ProviderEvent event = parse("SUCCESSFUL_DISBURSEMENT", "\"reference\":\"pb-a\",\"amount\":10");

        assertThat(event.eventId()).startsWith("sha256:");
    }

    @Test
    void collectionEventsAreNotActionable() {
        ProviderEvent event = parse("SUCCESSFUL_TRANSACTION", "\"transactionReference\":\"MNFY|1\",\"amountPaid\":100");

        assertThat(event.isActionable()).isFalse();
        assertThat(event.outcome()).isNull();
    }

    @Test
    void malformedBodiesAreRefused() {
        assertThatThrownBy(() -> provider.parse(bytes("not json"))).isInstanceOf(MalformedWebhookException.class);
        assertThatThrownBy(() -> provider.parse(bytes("[]"))).isInstanceOf(MalformedWebhookException.class);
        assertThatThrownBy(() -> provider.parse(bytes("{\"eventData\":{}}")))
                .isInstanceOf(MalformedWebhookException.class)
                .hasMessageContaining("eventType");
    }
}
