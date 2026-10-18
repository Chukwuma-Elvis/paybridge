package com.academy.paybridge.transfer.client;

import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.util.Map;
import java.util.function.Function;

/**
 * Paystack's transfer webhooks.
 * <ul>
 *   <li><b>Signature:</b> the {@code x-paystack-signature} header holds the HMAC-SHA512 of the raw body, keyed with your Paystack
 *       secret key, as hex.</li>
 *   <li><b>Events:</b> {@code transfer.success}, {@code transfer.failed}, {@code transfer.reversed}. The body is
 *       {@code {"event": "...", "data": {...}}} and {@code data.reference} is the reference WE gave Paystack when we asked it to send.</li>
 *   <li><b>Amounts</b> are whole kobo.</li>
 * </ul>
 * The signature recipe is consistent across everything I could find. The field names inside {@code data} come from third-party
 * guides and Paystack's examples, not from a delivery to this application: compare against your first real test-mode delivery.
 */
@Component
public class PaystackWebhookProvider implements WebhookProvider {

    static final String SIGNATURE_HEADER = "x-paystack-signature";

    private static final Map<String, GatewayOutcome> EVENTS = Map.of(
            "transfer.success", GatewayOutcome.SUCCESS,
            "transfer.failed", GatewayOutcome.FAILED,
            "transfer.reversed", GatewayOutcome.REVERSED);

    private final String secretKey;

    PaystackWebhookProvider(WebhookProperties properties) {
        this.secretKey = properties.paystack().secretKey();
    }

    @Override
    public String name() {
        return "paystack";
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
        return Hmac.signatureMatches(Hmac.hmacSha512Hex(secretKey, rawBody), header.apply(SIGNATURE_HEADER));
    }

    @Override
    public ProviderEvent parse(byte[] rawBody) {
        JsonNode root = WebhookJson.parseObject(rawBody);
        String event = WebhookJson.text(root, "event");
        if (event == null) {
            throw new MalformedWebhookException("The webhook has no 'event' field");
        }
        JsonNode data = root.path("data");

        String dataId = WebhookJson.text(data, "id");
        String eventId = dataId != null ? event + ":" + dataId : "sha256:" + Hmac.sha256Hex(rawBody);

        GatewayOutcome outcome = EVENTS.get(event);
        String reason = WebhookJson.firstNonBlank(
                WebhookJson.text(data, "complete_message"), WebhookJson.text(data, "reason"));

        return new ProviderEvent(
                eventId,
                event,
                outcome,
                WebhookJson.text(data, "reference"),
                WebhookJson.text(data, "transfer_code"),
                outcome == null ? null : WebhookJson.wholeNumber(data, "amount"),
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
