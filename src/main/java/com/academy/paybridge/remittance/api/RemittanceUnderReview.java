package com.academy.paybridge.remittance.api;

import java.util.UUID;

/** Published when a remittance needs a person's decision before anything can move. */
public record RemittanceUnderReview(UUID remittanceId, String reference, String reason) {
}
