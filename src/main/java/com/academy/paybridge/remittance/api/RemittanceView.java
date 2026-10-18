package com.academy.paybridge.remittance.api;

import com.academy.paybridge.fx.api.Corridor;

import java.time.Instant;
import java.util.UUID;

/**
 * A remittance as other modules and clients see it. The recipient's account number is masked (last four digits only), and no
 * identity details beyond the names are exposed.
 */
public record RemittanceView(UUID id, String reference, RemittanceStatus status, String statusReason, Corridor corridor,
                             long sellMinor, long feeMinor, long buyMinor, UUID quoteId, String partnerCode,
                             String partnerReference, String senderName, String recipientName, RecipientType recipientType,
                             String recipientAccountMasked, Instant fundingExpiresAt, Instant createdAt, Instant paidAt) {
}
