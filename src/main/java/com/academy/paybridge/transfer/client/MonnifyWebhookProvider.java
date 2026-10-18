package com.academy.paybridge.transfer.client;

import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.util.Map;
import java.util.function.Function;

/**
 * Monnify's disbursement webhooks.
 * <ul>
 *   <li><b>Signature:</b> the {@code monnify-signature} header. Monnify's documentation describes the recipe two ways (HMAC-SHA512
 *       of the raw body keyed with the client secret, and SHA-512 of the secret followed by the body). The first is what its
 *       sample code does and is the default; {@code paybridge.webhooks.monnify.signature-scheme} switches to the second.</li>
 *   <li><b>Events:</b> {@code SUCCESSFUL_DISBURSEMENT}, {@code FAILED_DISBURSEMENT}, {@code REVERSED_DISBURSEMENT}. The body is
 *       {@code {"eventType": "...", "eventData": {...}}} and {@code eventData.reference} is the reference WE gave Monnify.</li>
 *   <li><b>Amounts</b> are in NAIRA with decimals (2000.50), not kobo, so they are converted exactly.</li>
 * </ul>
 * Only the success payload is shown in Monnify's documentation and the sandbox may not sign at all, so this adapter is the least
 * verified part of Step 12. Compare it against your first real sandbox delivery.
 */
@Component
public class MonnifyWebhookProvider implements WebhookProvider {

    static final String SIGNATURE_HEADER = "monnify-signature";

    private static final Map<String, GatewayOutcome> EVENTS = Map.of(
            "SUCCESSFUL_DISBURSEMENT", GatewayOutcome.SUCCESS,
            "FAILED_DISBURSEMENT", GatewayOutcome.FAILED,
            "REVERSED_DISBURSEMENT", GatewayOutcome.REVERSED);

    private final String secretKey;
    private final MonnifySignatureScheme scheme;

    MonnifyWebhookProvider(WebhookProperties properties) {
        this.secretKey = properties.monnify().secretKey();
        this.scheme = properties.monnify().signatureScheme();
    }

    @Override
    public String name() {
        return "monnify";
    }

    @Override
    public boolean isConfigured() {
        return secretKey != null && !secretKey.isBlank();
    }

    @Override
    public boolean hasValidSignature(byte[] rawBody, Function<String, String> header) {
        if (!isConfigured()) {
            return false;
        }
        String expected = switch (scheme) {
            case HMAC_SHA512 -> Hmac.hmacSha512Hex(secretKey, rawBody);
            case SHA512_SECRET_PREFIX -> Hmac.sha512OfSecretThenMessageHex(secretKey, rawBody);
        };
        return Hmac.signatureMatches(expected, header.apply(SIGNATURE_HEADER));
    }

    @Override
    public ProviderEvent parse(byte[] rawBody) {
        JsonNode root = WebhookJson.parseObject(rawBody);
        String eventType = WebhookJson.text(root, "eventType");
        if (eventType == null) {
            throw new MalformedWebhookException("The webhook has no 'eventType' field");
        }
        JsonNode data = root.path("eventData");

        String transactionReference = WebhookJson.text(data, "transactionReference");
        String eventId = transactionReference != null
                ? eventType + ":" + transactionReference
                : "sha256:" + Hmac.sha256Hex(rawBody);

        GatewayOutcome outcome = EVENTS.get(eventType);
        String reason = WebhookJson.firstNonBlank(WebhookJson.text(data, "message"), WebhookJson.text(data, "reason"));

        return new ProviderEvent(
                eventId,
                eventType,
                outcome,
                WebhookJson.text(data, "reference"),
                transactionReference,
                outcome == null ? null : WebhookJson.nairaToKobo(data, "amount"),
                WebhookJson.text(data, "currency"),
                WebhookJson.clean(messageFor(outcome, reason)));
    }

    private static String messageFor(GatewayOutcome outcome, String reason) {
        if (outcome == null) {
            return null;
        }
        return switch (outcome) {
            case FAILED -> reason != null ? reason : "The transfer failed at the payment provider";
            case REVERSED -> reason != null ? reason : "The transfer was reversed by the payment provider";
            default -> reason;
        };
    }
}
