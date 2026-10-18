package com.academy.paybridge.compliance.api;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** A screening case as reviewers and other modules see it. */
public record CaseView(UUID id, String contextRef, String partyRole, String partyName, String partyCountry,
                       LocalDate partyBirth, ScreeningOutcome outcome, String matchDetails, CaseStatus status,
                       String decisionReason, String decidedBy, Instant decidedAt, Instant createdAt) {
}
