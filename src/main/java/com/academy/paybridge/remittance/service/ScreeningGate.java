package com.academy.paybridge.remittance.service;

import com.academy.paybridge.compliance.api.CaseStatus;
import com.academy.paybridge.compliance.api.CaseView;
import com.academy.paybridge.compliance.api.ComplianceApi;
import com.academy.paybridge.compliance.api.Party;
import com.academy.paybridge.compliance.api.ScreeningResult;
import com.academy.paybridge.compliance.api.UsdLimitScope;
import com.academy.paybridge.remittance.api.RemittanceStatus;
import com.academy.paybridge.remittance.api.RemittanceUnderReview;
import com.academy.paybridge.remittance.domain.Remittance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Compliance gates the flow. At creation it counts the amount against the sender's and recipient's USD limits, screens both people
 * and checks the recent pattern. Anything that is not clean parks the remittance in UNDER_REVIEW, where NOTHING moves until a person
 * decides. It joins the caller's transaction, so a refusal leaves no trace.
 * <p>
 * It logs reference numbers and case ids only, never the names of the people screened.
 */
@Component
class ScreeningGate {

    private static final Logger log = LoggerFactory.getLogger(ScreeningGate.class);

    private final ComplianceApi compliance;
    private final RemittanceAudit audit;
    private final RemittanceProperties properties;
    private final ApplicationEventPublisher events;

    ScreeningGate(ComplianceApi compliance, RemittanceAudit audit, RemittanceProperties properties, ApplicationEventPublisher events) {
        this.compliance = compliance;
        this.audit = audit;
        this.properties = properties;
        this.events = events;
    }

    /** Takes a CREATED remittance to AWAITING_FUNDING, or to UNDER_REVIEW if a person must look. */
    void run(Remittance r) {
        audit.move(r, RemittanceStatus.SCREENING, properties.screeningGateEnabled() ? null : "screening gate disabled (development only)");
        if (!properties.screeningGateEnabled()) {
            audit.move(r, RemittanceStatus.AWAITING_FUNDING, null);
            return;
        }

        // limits first: a refusal here must not leave cases behind (the whole transaction rolls back either way)
        LocalDate day = compliance.reserveUsd(UsdLimitScope.SENDER, r.getSenderKey(), r.getSellMinor());
        compliance.reserveUsd(UsdLimitScope.RECIPIENT, r.getRecipientKey(), r.getSellMinor());
        r.limitsReserved(day);

        ScreeningResult sender = compliance.screen(new Party(r.getSenderName(), r.getSenderCountry(), r.getSenderBirth()),
                "SENDER", r.getReference());
        ScreeningResult recipient = compliance.screen(new Party(r.getRecipientName(), r.getRecipientCountry(), null),
                "RECIPIENT", r.getReference());
        Optional<String> pattern = compliance.assessVelocity(r.getSenderKey(), r.getReference(), r.getSellMinor());
        UUID velocityCase = pattern.map(reason ->
                compliance.openReviewCase(r.getReference(), "VELOCITY", r.getSenderName(), reason).id()).orElse(null);
        r.recordCases(sender.caseId(), recipient.caseId(), velocityCase);

        if (sender.caseId() == null && recipient.caseId() == null && velocityCase == null) {
            audit.move(r, RemittanceStatus.AWAITING_FUNDING, null);
            return;
        }
        String reason = "Needs review: " + (sender.caseId() != null ? "sender " + sender.outcome() + "; " : "")
                + (recipient.caseId() != null ? "recipient " + recipient.outcome() + "; " : "")
                + pattern.map(p -> p.split(":")[0]).orElse("");
        audit.move(r, RemittanceStatus.UNDER_REVIEW, reason);
        events.publishEvent(new RemittanceUnderReview(r.getId(), r.getReference(), reason));
        log.warn("Remittance {} is under review", r.getReference());
    }

    /**
     * Looks at the cases behind an UNDER_REVIEW remittance. While any is still open nothing happens (returns false). Once all are
     * decided: one rejection fails the remittance and gives the limits back; otherwise it goes on to AWAITING_FUNDING.
     */
    boolean resolve(Remittance r) {
        if (r.getStatus() != RemittanceStatus.UNDER_REVIEW) {
            return false;
        }
        List<CaseView> cases = new ArrayList<>();
        for (UUID id : new UUID[]{r.getSenderCaseId(), r.getRecipientCaseId(), r.getVelocityCaseId(), r.getFundingCaseId()}) {
            if (id != null) {
                cases.add(compliance.getCase(id));
            }
        }
        if (cases.stream().anyMatch(c -> c.status() == CaseStatus.OPEN)) {
            return false;
        }
        Optional<CaseView> rejected = cases.stream().filter(c -> c.status() == CaseStatus.REJECTED).findFirst();
        if (rejected.isPresent()) {
            releaseLimits(r);
            audit.move(r, RemittanceStatus.FAILED, "Review rejected: " + rejected.get().decisionReason());
            return true;
        }
        audit.move(r, RemittanceStatus.AWAITING_FUNDING, "Review approved");
        return true;
    }

    /** Gives the USD allowance back (once), for a remittance that will not complete. */
    void releaseLimits(Remittance r) {
        if (!r.isLimitsReserved()) {
            return;
        }
        compliance.releaseUsd(UsdLimitScope.SENDER, r.getSenderKey(), r.getSellMinor(), r.getUsdUsageDate());
        compliance.releaseUsd(UsdLimitScope.RECIPIENT, r.getRecipientKey(), r.getSellMinor(), r.getUsdUsageDate());
        r.limitsReleased();
    }
}
