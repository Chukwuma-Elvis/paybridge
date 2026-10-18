package com.academy.paybridge.transfer.client;

import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/**
 * A corridor partner telling us that a sender's money has arrived ({@code POST /api/webhooks/partner}).
 * <ul>
 *   <li><b>Body:</b> {@code {"partner":"acme","eventId":"evt-1","type":"funding.confirmed","reference":"RM-...",
 *       "partnerReference":"...","amountMinor":100000,"currency":"USD","message":"..."}}.
 *       {@code reference} is the remittance reference WE gave the partner. Amounts are whole US cents.</li>
 *   <li><b>Events:</b> {@code funding.confirmed} and {@code funding.failed}; anything else is recorded and ignored.</li>
 *   <li><b>Signature:</b> the {@code x-partner-signature} header holds the HMAC-SHA512 of the raw body, keyed with that partner's
 *       shared secret, as hex.</li>
 * </ul>
 * The body names the partner, and that name is used ONLY to pick which secret to check the signature with. If the signature then
 * matches, the partner named is the partner who signed: one partner cannot speak for another without the other's secret.
 * <p>
 * This wire format is PayBridge's own contract for partners (there is no industry standard to copy). A real partner would be mapped
 * onto it, or given an adapter class like the Paystack and Monnify ones.
 */
@Component
public class PartnerWebhookProvider implements WebhookProvider {

    static final String SIGNATURE_HEADER = "x-partner-signature";

    private static final Map<String, GatewayOutcome> EVENTS = Map.of(
            "funding.confirmed", GatewayOutcome.SUCCESS,
            "funding.failed", GatewayOutcome.FAILED);

    private final Map<String, String> secrets = new HashMap<>();

    PartnerWebhookProvider(WebhookProperties properties) {
        properties.partners().forEach((code, partner) -> {
            if (partner != null && partner.secretKey() != null && !partner.secretKey().isBlank()) {
                secrets.put(code.toLowerCase(Locale.ROOT), partner.secretKey());
            }
        });
    }

    @Override
    public String name() {
        return "partner";
    }

    @Override
    public Kind kind() {
        return Kind.PARTNER_FUNDING;
    }

    @Override
    public boolean isConfigured() {
        return !secrets.isEmpty();
    }

    @Override
    public boolean hasValidSignature(byte[] rawBody, Function<String, String> header) {
        String partner = peekPartner(rawBody);
        String secret = partner == null ? null : secrets.get(partner);
        if (secret == null) {
            return false;
        }
        return Hmac.signatureMatches(Hmac.hmacSha512Hex(secret, rawBody), header.apply(SIGNATURE_HEADER));
    }

    @Override
    public ProviderEvent parse(byte[] rawBody) {
        JsonNode root = WebhookJson.parseObject(rawBody);
        String partner = cleanPartner(WebhookJson.text(root, "partner"));
        if (partner == null) {
            throw new MalformedWebhookException("The webhook has no valid 'partner' field");
        }
        String type = WebhookJson.text(root, "type");
        if (type == null) {
            throw new MalformedWebhookException("The webhook has no 'type' field");
        }
        String id = WebhookJson.text(root, "eventId");
        String eventId = partner + ":" + (id != null ? id : "sha256:" + Hmac.sha256Hex(rawBody));
        GatewayOutcome outcome = EVENTS.get(type);
        return new ProviderEvent(eventId, type, outcome, WebhookJson.text(root, "reference"),
                WebhookJson.text(root, "partnerReference"),
                outcome == null ? null : WebhookJson.wholeNumber(root, "amountMinor"),
                WebhookJson.text(root, "currency"),
                WebhookJson.clean(WebhookJson.text(root, "message")), partner);
    }

    /** Reads only the partner name, to choose the secret. Anything unreadable is simply "no partner". */
    private static String peekPartner(byte[] rawBody) {
        try {
            return cleanPartner(WebhookJson.text(WebhookJson.parseObject(rawBody), "partner"));
        } catch (MalformedWebhookException e) {
            return null;
        }
    }

    private static String cleanPartner(String partner) {
        if (partner == null) {
            return null;
        }
        String lower = partner.toLowerCase(Locale.ROOT);
        return lower.matches("[a-z0-9_-]{2,16}") ? lower : null;
    }
}
