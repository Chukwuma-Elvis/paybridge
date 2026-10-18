package com.academy.paybridge.remittance.service;

import com.academy.paybridge.account.api.AccountApi;
import com.academy.paybridge.account.api.AccountSummary;
import com.academy.paybridge.account.api.SystemAccount;
import com.academy.paybridge.fx.api.FxApi;
import com.academy.paybridge.ledger.api.EntryType;
import com.academy.paybridge.ledger.api.LedgerApi;
import com.academy.paybridge.ledger.api.PostedEntry;
import com.academy.paybridge.ledger.api.PostingLine;
import com.academy.paybridge.ledger.api.PostingRequest;
import com.academy.paybridge.remittance.api.RecipientType;
import com.academy.paybridge.remittance.api.RemittancePaid;
import com.academy.paybridge.remittance.api.RemittanceRefunded;
import com.academy.paybridge.remittance.api.RemittanceStatus;
import com.academy.paybridge.remittance.domain.Remittance;
import com.academy.paybridge.remittance.repository.RemittanceRepository;
import com.academy.paybridge.shared.exception.ErrorCode;
import com.academy.paybridge.shared.exception.PayBridgeException;
import com.academy.paybridge.shared.exception.ResourceNotFoundException;
import com.academy.paybridge.shared.money.Money;
import com.academy.paybridge.transfer.api.PayoutApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Currency;
import java.util.Optional;
import java.util.UUID;

/**
 * The money-moving steps after the sender's USD has arrived. Each method is ONE short transaction that:
 * <ol>
 *   <li>locks the remittance and checks it is in the status this step expects (otherwise it quietly does nothing),</li>
 *   <li>posts to the ledger using a reference that is unique to the step (so repeating the step posts nothing twice),</li>
 *   <li>moves the status and writes the history, in the same transaction.</li>
 * </ol>
 * If the process dies between two steps, the stored status says exactly where to resume, and repeating a finished step is harmless.
 * None of these methods talks to an outside system; the outside work is inside {@link PayoutApi}, which queues it.
 */
@Component
@Transactional
class RemittanceStepper {

    private static final Logger log = LoggerFactory.getLogger(RemittanceStepper.class);
    private static final Currency NGN = Currency.getInstance("NGN");
    private static final Duration MAX_BACKOFF = Duration.ofMinutes(5);

    private final RemittanceRepository repository;
    private final FxApi fx;
    private final LedgerApi ledger;
    private final AccountApi accounts;
    private final PayoutApi payouts;
    private final ScreeningGate gate;
    private final RemittanceAudit audit;
    private final RemittanceProperties properties;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    RemittanceStepper(RemittanceRepository repository, FxApi fx, LedgerApi ledger, AccountApi accounts, PayoutApi payouts,
                      ScreeningGate gate, RemittanceAudit audit, RemittanceProperties properties, ApplicationEventPublisher events,
                      Clock clock) {
        this.repository = repository;
        this.fx = fx;
        this.ledger = ledger;
        this.accounts = accounts;
        this.payouts = payouts;
        this.gate = gate;
        this.audit = audit;
        this.properties = properties;
        this.events = events;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    RemittanceStatus statusOf(UUID id) {
        return repository.findById(id).map(Remittance::getStatus).orElse(null);
    }

    /** FUNDED to CONVERTED: the quote was used up at creation; this carries it out in the ledger (both legs or neither). */
    void convert(UUID id) {
        Remittance r = lock(id);
        if (r.getStatus() != RemittanceStatus.FUNDED) {
            return;
        }
        fx.convert(r.getQuoteId(), accounts.systemAccountId(SystemAccount.REMITTANCE_CLEARING_USD),
                accounts.systemAccountId(SystemAccount.REMITTANCE_CLEARING_NGN));
        audit.move(r, RemittanceStatus.CONVERTED, null);
        r.stepSucceeded();
    }

    /** CONVERTED to PAID (a PayBridge recipient) or to PAYOUT_PENDING (a recipient at another bank). */
    void payout(UUID id) {
        Remittance r = lock(id);
        if (r.getStatus() != RemittanceStatus.CONVERTED) {
            return;
        }
        UUID clearingNgn = accounts.systemAccountId(SystemAccount.REMITTANCE_CLEARING_NGN);
        if (r.getRecipientType() == RecipientType.PAYBRIDGE) {
            AccountSummary recipient = findUsableRecipient(r.getRecipientAccountNumber());
            if (recipient == null) {
                toRefund(r, "The recipient account can no longer receive naira");
                return;
            }
            ledger.post(PostingRequest.of(EntryType.REMITTANCE_PAYOUT, r.getReference(), "Remittance " + r.getReference() + " paid out",
                    new PostingLine(clearingNgn, Money.ofMinor(-r.getBuyMinor(), NGN)),
                    new PostingLine(recipient.id(), Money.ofMinor(r.getBuyMinor(), NGN))));
            r.markPaid(clock.instant());
            audit.move(r, RemittanceStatus.PAID, null);
            events.publishEvent(new RemittancePaid(r.getId(), r.getReference(), recipient.ownerCustomerId(), r.getBuyMinor()));
            return;
        }
        String payoutReference = "PO-" + r.getReference();
        payouts.request(new PayoutApi.PayoutRequest(payoutReference, clearingNgn, r.getBuyMinor(), r.getRecipientBankCode(),
                r.getRecipientAccountNumber(), r.getRecipientName(), "Remittance " + r.getReference()));
        r.recordPayoutReference(payoutReference);
        audit.move(r, RemittanceStatus.PAYOUT_PENDING, null);
        r.waitUntil(clock.instant().plus(properties.processor().payoutPollDelay()));
    }

    /** PAYOUT_PENDING: ask how the payout is going. Paid, refund, or look again later. */
    void checkPayout(UUID id) {
        Remittance r = lock(id);
        if (r.getStatus() != RemittanceStatus.PAYOUT_PENDING) {
            return;
        }
        Optional<PayoutApi.PayoutView> payout = payouts.find(r.getPayoutReference());
        if (payout.isEmpty()) {
            throw new IllegalStateException("Remittance " + r.getReference() + " has no payout " + r.getPayoutReference());
        }
        switch (payout.get().status()) {
            case SUCCESS -> {
                r.markPaid(clock.instant());
                audit.move(r, RemittanceStatus.PAID, null);
                events.publishEvent(new RemittancePaid(r.getId(), r.getReference(), null, r.getBuyMinor()));
            }
            case FAILED -> toRefund(r, "The payout failed: " + payout.get().failureReason());
            case PENDING, PROCESSING -> r.waitUntil(clock.instant().plus(properties.processor().payoutPollDelay()));
        }
    }

    /**
     * REFUND_PENDING to REFUNDED: undo the conversion (both legs), then undo the funding so the sender's USD is back with the partner,
     * then give the limits back. Every undo is a reversing ledger entry, never an edit, and each is idempotent.
     */
    void refund(UUID id) {
        Remittance r = lock(id);
        if (r.getStatus() != RemittanceStatus.REFUND_PENDING) {
            return;
        }
        String reason = r.getStatusReason() == null ? "Remittance could not be completed" : r.getStatusReason();
        if (fx.findConversion(r.getQuoteId()).filter(c -> !c.reversed()).isPresent()) {
            fx.reverseConversion(r.getQuoteId(), reason);
        }
        PostedEntry funding = ledger.find(EntryType.REMITTANCE_FUNDING, r.getReference())
                .orElseThrow(() -> new IllegalStateException("No funding entry for remittance " + r.getReference()));
        ledger.reverse(funding.id(), "refund:" + r.getReference(), "Refund: " + reason);
        gate.releaseLimits(r);
        audit.move(r, RemittanceStatus.REFUNDED, reason);
        r.stepSucceeded();
        events.publishEvent(new RemittanceRefunded(r.getId(), r.getReference(), r.getSellMinor(), reason));
    }

    /** A step failed. Try again later with a growing delay; after too many tries a funded remittance is refunded rather than stuck. */
    void recordFailure(UUID id, String error) {
        Remittance r = lock(id);
        if (r.getStatus().isFinal()) {
            return;
        }
        int attempt = r.getAttempts() + 1;
        long multiplier = 1L << Math.min(r.getAttempts(), 20);
        Duration delay = properties.processor().baseDelay().multipliedBy(multiplier);
        if (delay.compareTo(MAX_BACKOFF) > 0) {
            delay = MAX_BACKOFF;
        }
        r.stepFailed(error, clock.instant().plus(delay));
        RemittanceStatus status = r.getStatus();
        if (attempt >= properties.processor().maxAttempts() && (status == RemittanceStatus.FUNDED || status == RemittanceStatus.CONVERTED)) {
            log.error("Remittance {} could not be processed after {} attempts; refunding", r.getReference(), attempt);
            toRefund(r, "The remittance could not be processed: " + error);
        }
    }

    private void toRefund(Remittance r, String reason) {
        audit.move(r, RemittanceStatus.REFUND_PENDING, reason);
        r.stepSucceeded();
    }

    private AccountSummary findUsableRecipient(String accountNumber) {
        try {
            AccountSummary account = accounts.getByAccountNumber(accountNumber);
            return account.isCustomerAccount() && account.isActive() && NGN.equals(account.currency()) ? account : null;
        } catch (ResourceNotFoundException e) {
            return null;
        }
    }

    private Remittance lock(UUID id) {
        return repository.findByIdForUpdate(id)
                .orElseThrow(() -> new PayBridgeException(ErrorCode.RESOURCE_NOT_FOUND, "No remittance " + id));
    }

    @SuppressWarnings("unused")
    private Instant now() {
        return clock.instant();
    }
}
