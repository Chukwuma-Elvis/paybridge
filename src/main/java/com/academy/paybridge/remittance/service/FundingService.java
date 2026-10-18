package com.academy.paybridge.remittance.service;

import com.academy.paybridge.account.api.AccountApi;
import com.academy.paybridge.account.api.SystemAccount;
import com.academy.paybridge.compliance.api.ComplianceApi;
import com.academy.paybridge.ledger.api.EntryType;
import com.academy.paybridge.ledger.api.LedgerApi;
import com.academy.paybridge.ledger.api.PostingLine;
import com.academy.paybridge.ledger.api.PostingRequest;
import com.academy.paybridge.remittance.api.RemittanceStatus;
import com.academy.paybridge.remittance.api.RemittanceUnderReview;
import com.academy.paybridge.remittance.domain.Remittance;
import com.academy.paybridge.remittance.repository.RemittanceRepository;
import com.academy.paybridge.shared.exception.ErrorCode;
import com.academy.paybridge.shared.exception.PayBridgeException;
import com.academy.paybridge.shared.money.Money;
import com.academy.paybridge.transfer.api.PartnerFundingHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import org.springframework.data.domain.PageRequest;

import java.time.Clock;
import java.time.Instant;
import java.util.Currency;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Takes in the sender's money when the corridor partner confirms it. Reached two ways, both ending in {@link #applyFunding}: the
 * partner's signed webhook (through the generalised webhook pipeline) and the funding check that asks the partner when no webhook
 * came. Applying it is idempotent, so the two can never conflict.
 */
@Service
@Transactional
public class FundingService implements PartnerFundingHandler {

    /** The code of the ledger account that records what a partner holds for us (or owes us), one per partner. */
    static String partnerAccountCode(String partner) {
        return "PARTNER_SETTLEMENT_USD:" + partner.toLowerCase(Locale.ROOT);
    }

    private static final Logger log = LoggerFactory.getLogger(FundingService.class);
    private static final Currency USD = Currency.getInstance("USD");

    private final RemittanceRepository repository;
    private final LedgerApi ledger;
    private final AccountApi accounts;
    private final ComplianceApi compliance;
    private final ScreeningGate gate;
    private final RemittanceAudit audit;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    FundingService(RemittanceRepository repository, LedgerApi ledger, AccountApi accounts, ComplianceApi compliance,
                   ScreeningGate gate, RemittanceAudit audit, ApplicationEventPublisher events, Clock clock) {
        this.repository = repository;
        this.ledger = ledger;
        this.accounts = accounts;
        this.compliance = compliance;
        this.gate = gate;
        this.audit = audit;
        this.events = events;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ExpectedFunding> expectedFunding(String partner, String reference) {
        return repository.findByReference(reference)
                .filter(r -> r.getPartnerCode().equalsIgnoreCase(partner))
                .map(r -> new ExpectedFunding(r.getSellMinor(), "USD"));
    }

    @Override
    public FundingResult applyFunding(String partner, String reference, boolean funded, String partnerReference, String message) {
        Remittance r = repository.findByReferenceForUpdate(reference)
                .filter(found -> found.getPartnerCode().equalsIgnoreCase(partner))
                .orElseThrow(() -> new PayBridgeException(ErrorCode.RESOURCE_NOT_FOUND, "No remittance " + reference + " for that partner"));
        RemittanceStatus status = r.getStatus();

        switch (status) {
            case AWAITING_FUNDING -> {
                if (funded) {
                    postFunding(r);
                } else {
                    gate.releaseLimits(r);
                    audit.move(r, RemittanceStatus.FAILED, "The partner reported that funding failed"
                            + (message == null ? "" : ": " + message));
                }
                return FundingResult.APPLIED;
            }
            case CREATED, SCREENING, UNDER_REVIEW -> {
                // not cleared yet: nothing may move. The funding check will ask the partner again once it is cleared.
                return FundingResult.HELD;
            }
            case FUNDED, CONVERTED, PAYOUT_PENDING, PAID, REFUND_PENDING, REFUNDED -> {
                if (!funded) {
                    throw new PayBridgeException(ErrorCode.INVALID_STATE, "Remittance " + reference
                            + " is already funded but the partner now says funding failed: MANUAL REVIEW NEEDED");
                }
                return FundingResult.APPLIED;        // the partner repeated itself
            }
            case FAILED, CANCELLED -> {
                if (funded) {
                    throw new PayBridgeException(ErrorCode.INVALID_STATE, "Remittance " + reference + " is " + status
                            + " but the partner says it holds the sender's money: MANUAL REVIEW NEEDED");
                }
                return FundingResult.APPLIED;
            }
        }
        return FundingResult.APPLIED;
    }

    @Override
    public void flagMismatch(String partner, String reference, String detail) {
        Remittance r = repository.findByReferenceForUpdate(reference).orElse(null);
        if (r == null || r.getStatus() != RemittanceStatus.AWAITING_FUNDING || r.getFundingCaseId() != null) {
            return;     // nothing waiting, or already raised once: do not raise it again every time the partner repeats itself
        }
        UUID caseId = compliance.openReviewCase(reference, "FUNDING", r.getSenderName(), "Funding mismatch: " + detail).id();
        r.recordFundingCase(caseId);
        audit.move(r, RemittanceStatus.UNDER_REVIEW, "Funding amount disagrees with the remittance");
        events.publishEvent(new RemittanceUnderReview(r.getId(), reference, "Funding amount disagrees with the remittance"));
        log.error("Remittance {} received a funding that disagrees with it; sent for review", reference);
    }

    /** A remittance whose funding window has run out without the partner ever confirming: fail it and give the limits back. */
    public void expire(String reference) {
        Remittance r = repository.findByReferenceForUpdate(reference).orElse(null);
        if (r == null || r.getStatus() != RemittanceStatus.AWAITING_FUNDING || r.getFundingExpiresAt().isAfter(clock.instant())) {
            return;
        }
        gate.releaseLimits(r);
        audit.move(r, RemittanceStatus.FAILED, "The funding window expired before the partner confirmed the money");
    }

    @Transactional(readOnly = true)
    public List<String> awaitingFundingOlderThan(Instant before, int page, int pageSize) {
        return repository.findReferencesByStatusCreatedBefore(RemittanceStatus.AWAITING_FUNDING, before, PageRequest.of(page, pageSize));
    }

    @Transactional(readOnly = true)
    public Optional<String> partnerOf(String reference) {
        return repository.findByReference(reference).map(Remittance::getPartnerCode);
    }

    private void postFunding(Remittance r) {
        UUID clearing = accounts.systemAccountId(SystemAccount.REMITTANCE_CLEARING_USD);
        UUID partnerAccount = accounts.ensureSystemAccount(partnerAccountCode(r.getPartnerCode()), "USD");
        ledger.post(PostingRequest.of(EntryType.REMITTANCE_FUNDING, r.getReference(), "Funding for remittance " + r.getReference(),
                new PostingLine(clearing, Money.ofMinor(r.getSellMinor(), USD)),
                new PostingLine(partnerAccount, Money.ofMinor(-r.getSellMinor(), USD))));
        r.markFunded(clock.instant());
        audit.move(r, RemittanceStatus.FUNDED, null);
    }
}
