package com.academy.paybridge.remittance.api;

import java.util.List;
import java.util.UUID;

/** The front desk of the remittance module. */
public interface RemittanceApi {

    /**
     * Starts a remittance from a quote. Idempotent per ({@code clientName}, {@code idempotencyKey}): the same request again returns
     * the same remittance; the same key with a different request is refused.
     * <p>
     * In one transaction it uses up the quote, screens the sender and the recipient, counts the amount against their USD limits and
     * records the remittance. If anything is refused (an expired quote, a limit), nothing is left behind and the quote is still usable.
     */
    Created create(CreateRemittance command, String clientName, String idempotencyKey);

    /** @throws com.academy.paybridge.shared.exception.ResourceNotFoundException if there is no such remittance */
    RemittanceView get(UUID remittanceId);

    RemittanceView getByReference(String reference);

    /** Newest first. */
    List<RemittanceView> listForSender(String partnerCode, String senderReference, int limit);

    /** The remittance plus whether this call created it (false when it is a replay). */
    record Created(RemittanceView remittance, boolean created) {
    }
}
