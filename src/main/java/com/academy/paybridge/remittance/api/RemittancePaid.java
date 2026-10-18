package com.academy.paybridge.remittance.api;

import java.util.UUID;

/** Published when the recipient has the money. {@code recipientCustomerId} is null when the recipient is not a PayBridge customer. */
public record RemittancePaid(UUID remittanceId, String reference, UUID recipientCustomerId, long buyMinor) {
}
