package com.academy.paybridge.remittance.api;

import java.util.UUID;

/** Published when the sender's USD has been returned because the remittance could not be completed. */
public record RemittanceRefunded(UUID remittanceId, String reference, long sellMinor, String reason) {
}
