package com.academy.paybridge.transfer.client;

/**
 * A webhook, with the provider's own vocabulary translated into ours. Paystack says {@code transfer.success}, Monnify says
 * {@code SUCCESSFUL_DISBURSEMENT}; both become {@link GatewayOutcome#SUCCESS}. Everything after this point in PayBridge
 * is the same whichever provider sent it.
 *
 * @param eventId           what makes this delivery recognisable when the provider sends it again
 * @param eventType         the provider's own name for the event, for the record
 * @param outcome           what happened to the transfer, or null for an event we do not act on
 * @param reference         OUR reference for the transfer (the one we gave the provider), or null if the event carried none
 * @param providerReference the provider's own id for the transfer
 * @param amountMinor       the amount in kobo, or null if the event did not say
 * @param currency          the currency code, or null if the event did not say
 * @param message           the provider's reason, cleaned and shortened, or null
 * @param partner           for a corridor partner's event, which partner sent it (the provider name is then just "partner");
 *                          otherwise null
 */
public record ProviderEvent(
        String eventId,
        String eventType,
        GatewayOutcome outcome,
        String reference,
        String providerReference,
        Long amountMinor,
        String currency,
        String message,
        String partner) {

    /** An event from a payment provider, which has no separate partner identity. */
    public ProviderEvent(String eventId, String eventType, GatewayOutcome outcome, String reference, String providerReference,
                         Long amountMinor, String currency, String message) {
        this(eventId, eventType, outcome, reference, providerReference, amountMinor, currency, message, null);
    }

    /** True when this is an event about a transfer outcome that we should act on. */
    public boolean isActionable() {
        return outcome != null;
    }
}
