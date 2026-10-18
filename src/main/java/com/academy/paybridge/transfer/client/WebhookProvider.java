package com.academy.paybridge.transfer.client;

import java.util.function.Function;

/**
 * One payment provider's webhooks: how to check they really came from the provider, and how to read them.
 * <p>
 * Everything provider-specific about webhooks lives behind this interface (the header names, the signature recipe, the
 * shape of the JSON, the event names). The rest of PayBridge only ever sees a {@link ProviderEvent}. Supporting a new
 * provider means writing one class that implements this.
 */
public interface WebhookProvider {

    /** What a provider's events are about. The pipeline (signature, de-duplication, cross-check) is the same; what is applied differs. */
    enum Kind {
        /** Outcomes of transfers WE asked the provider to make. */
        TRANSFER_OUTCOME,
        /** A corridor partner telling us the sender's money has arrived. */
        PARTNER_FUNDING
    }

    default Kind kind() {
        return Kind.TRANSFER_OUTCOME;
    }

    /** The name used in the address, for example {@code paystack} in {@code /api/webhooks/paystack}. */
    String name();

    /** False until the provider's secret has been set. An unconfigured provider accepts nothing. */
    boolean isConfigured();

    /**
     * Is the signature genuine? The check is made on the RAW bytes exactly as they arrived: parsing the JSON and writing it
     * out again can change a byte, and then no signature would ever match.
     *
     * @param rawBody the request body, untouched
     * @param header  looks up a request header by name (case-insensitive)
     */
    boolean hasValidSignature(byte[] rawBody, Function<String, String> header);

    /**
     * Reads the body. Throws {@link MalformedWebhookException} if it is not the JSON we expect. An event we do not act on
     * is NOT an error: it comes back with a null outcome.
     */
    ProviderEvent parse(byte[] rawBody);
}
