package com.academy.paybridge.compliance.api;

import com.academy.paybridge.shared.money.Money;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The front desk of the compliance module: the rules about how much a customer may send, and the anti-money-laundering checks
 * (screening, USD limits, velocity) that decide whether a payment may proceed.
 * The naira limits depend on the customer's KYC tier.
 */
public interface ComplianceApi {

    /**
     * Checks the amount against the customer's per-transaction and daily limits and, if it passes, counts it
     * towards today's total in one atomic step. Call it inside the transfer's transaction: if the transfer later
     * fails and rolls back, the reservation rolls back with it.
     *
     * @throws com.academy.paybridge.shared.exception.PayBridgeException {@code LIMIT_EXCEEDED} if a limit would be broken
     */
    void reserve(UUID customerId, Money amount);

    /** Gives back a reservation, for example when an external transfer later fails. */
    void release(UUID customerId, Money amount, LocalDate usageDate);

    /** The Africa/Lagos calendar day that "today's" limit is counted against. */
    LocalDate today();

    LimitStatus limitStatus(UUID customerId);

    // ---- anti-money-laundering (Step 17)

    /**
     * Screens a party against the watch list. CLEAR needs no follow-up. REVIEW and BLOCK open a permanent case. Asking again for the
     * same ({@code contextRef}, {@code partyRole}) returns the earlier answer instead of opening a second case.
     */
    ScreeningResult screen(Party party, String partyRole, String contextRef);

    /**
     * Counts {@code usdMinor} (US cents) towards the subject's daily and monthly USD allowance in one atomic step.
     * Returns the day it was counted against, which {@link #releaseUsd} needs later.
     *
     * @throws com.academy.paybridge.shared.exception.PayBridgeException {@code LIMIT_EXCEEDED} if a limit would be broken
     */
    LocalDate reserveUsd(UsdLimitScope scope, String subjectKey, long usdMinor);

    void releaseUsd(UsdLimitScope scope, String subjectKey, long usdMinor, LocalDate usageDate);

    /**
     * Records the attempt and says whether the recent pattern looks wrong (too many in a short time, or several just under the
     * reporting threshold). A reason means "send for review"; empty means nothing unusual. Recording is idempotent per {@code eventRef}.
     */
    Optional<String> assessVelocity(String subjectKey, String eventRef, long usdMinor);

    /**
     * Opens a REVIEW case for something other than a name match (an unusual pattern, a payment amount that disagrees with the record).
     * Idempotent per ({@code contextRef}, {@code partyRole}): asking again returns the existing case.
     */
    CaseView openReviewCase(String contextRef, String partyRole, String partyName, String reason);

    /** @throws com.academy.paybridge.shared.exception.ResourceNotFoundException if there is no such case */
    CaseView getCase(UUID caseId);

    /** Newest first. */
    List<CaseView> listCases(CaseStatus status, int limit);

    /**
     * Records a person's decision with a mandatory reason. Idempotent: repeating the same decision changes nothing; the opposite
     * decision on a decided case is refused. A BLOCK case can only be rejected.
     */
    CaseView decideCase(UUID caseId, CaseDecision decision, String reason, String decidedBy);
}
