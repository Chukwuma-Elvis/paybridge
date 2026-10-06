package com.academy.paybridge.transfer.service;

import com.academy.paybridge.account.api.AccountApi;
import com.academy.paybridge.account.api.AccountSummary;
import com.academy.paybridge.account.api.SystemAccount;
import com.academy.paybridge.compliance.api.ComplianceApi;
import com.academy.paybridge.customer.api.CustomerApi;
import com.academy.paybridge.customer.api.CustomerSummary;
import com.academy.paybridge.ledger.api.EntryType;
import com.academy.paybridge.ledger.api.LedgerApi;
import com.academy.paybridge.ledger.api.PostedEntry;
import com.academy.paybridge.ledger.api.PostingLine;
import com.academy.paybridge.ledger.api.PostingRequest;
import com.academy.paybridge.shared.exception.ErrorCode;
import com.academy.paybridge.shared.exception.PayBridgeException;
import com.academy.paybridge.shared.exception.ResourceNotFoundException;
import com.academy.paybridge.shared.money.Money;
import com.academy.paybridge.transfer.api.TransferCompleted;
import com.academy.paybridge.transfer.api.TransferFailed;
import com.academy.paybridge.transfer.api.TransferReversed;
import com.academy.paybridge.transfer.api.TransferStatus;
import com.academy.paybridge.transfer.client.GatewayOutcome;
import com.academy.paybridge.transfer.client.TransferInstruction;
import com.academy.paybridge.transfer.domain.Beneficiary;
import com.academy.paybridge.transfer.domain.Transfer;
import com.academy.paybridge.transfer.domain.TransferOutbox;
import com.academy.paybridge.transfer.repository.BeneficiaryRepository;
import com.academy.paybridge.transfer.repository.TransferOutboxRepository;
import com.academy.paybridge.transfer.repository.TransferRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Everything that changes the database for an external transfer. Every public method is ONE short transaction.
 * <p>
 * None of these methods ever talks to the payment provider. The {@link TransferDispatcher} and the
 * {@link TransferReconciler} do that, outside any transaction, and then call back in here with what they learned.
 * <p>
 * The money moves in two steps, through the SUSPENSE account (the "in transit" shelf):
 * <pre>
 *   1. hold        customer  -(amount + fee)      suspense  +(amount + fee)
 *   2a. settle     suspense  -(amount + fee)      paystack  +amount    fees +fee      (provider succeeded)
 *   2b. release    the hold is reversed: the customer gets everything back              (provider failed)
 * </pre>
 */
@Service
@Transactional
public class ExternalTransferService {

    private static final Logger log = LoggerFactory.getLogger(ExternalTransferService.class);
    private static final Duration MAX_BACKOFF = Duration.ofMinutes(5);

    private final TransferRepository transferRepository;
    private final TransferOutboxRepository outboxRepository;
    private final BeneficiaryRepository beneficiaryRepository;
    private final IdempotencyService idempotencyService;
    private final AccountApi accountApi;
    private final CustomerApi customerApi;
    private final ComplianceApi complianceApi;
    private final LedgerApi ledgerApi;
    private final FeeCalculator feeCalculator;
    private final TransferProperties properties;
    private final ApplicationEventPublisher events;
    private final Clock clock;
    private final int maxAttempts;
    private final Duration baseDelay;

    public ExternalTransferService(TransferRepository transferRepository,
                                   TransferOutboxRepository outboxRepository,
                                   BeneficiaryRepository beneficiaryRepository,
                                   IdempotencyService idempotencyService,
                                   AccountApi accountApi,
                                   CustomerApi customerApi,
                                   ComplianceApi complianceApi,
                                   LedgerApi ledgerApi,
                                   FeeCalculator feeCalculator,
                                   TransferProperties properties,
                                   ApplicationEventPublisher events,
                                   Clock clock,
                                   @Value("${paybridge.dispatcher.max-attempts:5}") int maxAttempts,
                                   @Value("${paybridge.dispatcher.base-delay:2s}") Duration baseDelay) {
        this.transferRepository = transferRepository;
        this.outboxRepository = outboxRepository;
        this.beneficiaryRepository = beneficiaryRepository;
        this.idempotencyService = idempotencyService;
        this.accountApi = accountApi;
        this.customerApi = customerApi;
        this.complianceApi = complianceApi;
        this.ledgerApi = ledgerApi;
        this.feeCalculator = feeCalculator;
        this.properties = properties;
        this.events = events;
        this.clock = clock;
        this.maxAttempts = maxAttempts;
        this.baseDelay = baseDelay;
    }

    // ------------------------------------------------------------------------------------------------
    // Step 1: accept the request (fast, no provider call)
    // ------------------------------------------------------------------------------------------------

    /**
     * Accepts an external transfer: parks the customer's money (amount + fee) in suspense, records the transfer as
     * PENDING and writes the outbox note, all in one transaction. The provider is contacted LATER by the dispatcher.
     */
    public TransferResult initiate(ExternalTransferCommand command, String idempotencyKey) {
        String scope = "transfer:" + command.sourceAccountId();
        String fingerprint = IdempotencyService.fingerprint("external", command.sourceAccountId(),
                command.beneficiaryId(), command.amountMinor(), command.narration());

        Optional<UUID> alreadyDone = idempotencyService.begin(scope, idempotencyKey, fingerprint);
        if (alreadyDone.isPresent()) {
            Transfer existing = transferRepository.findById(alreadyDone.get())
                    .orElseThrow(() -> new ResourceNotFoundException("Transfer", alreadyDone.get()));
            return new TransferResult(TransferMapper.toSummary(existing), true);
        }

        AccountSummary source = accountApi.getAccount(command.sourceAccountId());
        TransferRules.requireUsableCustomerAccount(source, "Source");
        CustomerSummary owner = customerApi.getCustomer(source.ownerCustomerId());
        if (!owner.isActive()) {
            throw new PayBridgeException(ErrorCode.ACCOUNT_NOT_ACTIVE, "The sending customer is not active");
        }

        Beneficiary beneficiary = beneficiaryRepository.findById(command.beneficiaryId())
                .filter(found -> found.getCustomerId().equals(owner.id()))   // you can only pay your own payees
                .orElseThrow(() -> new ResourceNotFoundException("Beneficiary", command.beneficiaryId()));

        Money amount = Money.ofMinor(command.amountMinor(), source.currency());
        if (amount.minorUnits() < properties.externalMinMinor()) {
            throw new PayBridgeException(ErrorCode.VALIDATION_FAILED,
                    "The smallest external transfer is " + naira(properties.externalMinMinor()));
        }
        Money fee = feeCalculator.feeFor(amount);
        Money total = amount.add(fee);

        complianceApi.reserve(owner.id(), amount);

        Transfer transfer = transferRepository.save(Transfer.external(owner.id(), source.id(), beneficiary.getId(),
                amount, fee, command.narration(), complianceApi.today()));

        ledgerApi.post(PostingRequest.of(EntryType.EXTERNAL_HOLD, transfer.getReference(),
                "Hold for external transfer to " + beneficiary.getAccountNumber(),
                new PostingLine(source.id(), total.negate()),
                new PostingLine(accountApi.systemAccountId(SystemAccount.SUSPENSE), total)));

        outboxRepository.save(TransferOutbox.forTransfer(transfer.getId(), clock.instant()));
        idempotencyService.complete(scope, idempotencyKey, transfer.getId());

        return new TransferResult(TransferMapper.toSummary(transfer), false);
    }

    // ------------------------------------------------------------------------------------------------
    // Step 2: the dispatcher's building blocks (each one is a separate short transaction)
    // ------------------------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<UUID> dueTransferIds(int batchSize) {
        return outboxRepository.findDueTransferIds(TransferOutbox.Status.PENDING, clock.instant(),
                PageRequest.of(0, batchSize));
    }

    /**
     * Marks the transfer as being sent and returns what to send. Returns empty if there is nothing (left) to send.
     * Calling it twice is safe: if the process crashed after this step, the next run simply sends again, and the
     * provider ignores the repeat because the reference is the same.
     */
    public Optional<TransferInstruction> beginDispatch(UUID transferId) {
        Transfer transfer = transferRepository.findByIdForUpdate(transferId)
                .orElseThrow(() -> new ResourceNotFoundException("Transfer", transferId));
        TransferOutbox outbox = outbox(transferId);

        if (outbox.getStatus() != TransferOutbox.Status.PENDING
                || transfer.getStatus().isFinal()
                || transfer.getStatus() == TransferStatus.SUCCESS) {
            outbox.markDone();
            return Optional.empty();
        }
        if (transfer.getStatus() == TransferStatus.PENDING) {
            transfer.markProcessing();
        }

        Beneficiary beneficiary = beneficiaryRepository.findById(transfer.getBeneficiaryId())
                .orElseThrow(() -> new ResourceNotFoundException("Beneficiary", transfer.getBeneficiaryId()));
        return Optional.of(new TransferInstruction(transfer.getReference(), transfer.getAmountMinor(),
                transfer.getCurrency(), beneficiary.getProviderRecipientCode(), transfer.getNarration()));
    }

    /** The provider accepted the transfer and is still working on it. The final answer will come later. */
    public void recordAccepted(UUID transferId, String providerReference) {
        Transfer transfer = transferRepository.findByIdForUpdate(transferId)
                .orElseThrow(() -> new ResourceNotFoundException("Transfer", transferId));
        if (providerReference != null) {
            transfer.recordProviderReference(providerReference);
        }
        outbox(transferId).markDone();
    }

    /**
     * We could not get a clear answer from the provider. This does NOT mean it failed, so we do not touch the
     * customer's money: we just try again later, and after too many tries we hand over to the reconciler.
     */
    public void scheduleRetryOrGiveUp(UUID transferId, String error) {
        TransferOutbox outbox = outbox(transferId);
        if (outbox.getAttempts() + 1 >= maxAttempts) {
            outbox.giveUp(error);
            log.warn("Giving up sending transfer {} after {} attempts; the reconciler will ask the provider: {}",
                    transferId, outbox.getAttempts(), error);
            return;
        }
        long multiplier = 1L << Math.min(outbox.getAttempts(), 20);
        Duration delay = baseDelay.multipliedBy(multiplier);
        if (delay.compareTo(MAX_BACKOFF) > 0) {
            delay = MAX_BACKOFF;
        }
        outbox.scheduleRetry(clock.instant().plus(delay), error);
    }

    // ------------------------------------------------------------------------------------------------
    // Step 3: the provider told us the outcome (from the dispatcher, the reconciler or, later, a webhook)
    // ------------------------------------------------------------------------------------------------

    /**
     * Applies what the provider says to the transfer. Safe to call repeatedly with the same answer, which matters
     * because providers (and webhooks) repeat themselves.
     */
    public void applyProviderResult(String reference, GatewayOutcome outcome, String providerReference, String message) {
        Transfer transfer = transferRepository.findByReferenceForUpdate(reference)
                .orElseThrow(() -> new ResourceNotFoundException("Transfer", reference));

        switch (outcome) {
            case SUCCESS -> applySuccess(transfer, providerReference);
            case FAILED -> applyFailure(transfer, message == null ? "Rejected by the payment provider" : message);
            case REVERSED -> {
                if (transfer.getStatus() == TransferStatus.SUCCESS) {
                    applyReversal(transfer, message == null ? "Reversed by the payment provider" : message);
                } else {
                    applyFailure(transfer, message == null ? "Reversed by the payment provider" : message);
                }
            }
            case PENDING, NOT_FOUND -> {
                // nothing to do: still waiting (NOT_FOUND is only acted on by the reconciler, see failIfNeverSent)
            }
        }
    }

    /** The provider has no record of the transfer, so it was never sent. Safe to refund. */
    public void failIfNeverSent(String reference) {
        Transfer transfer = transferRepository.findByReferenceForUpdate(reference)
                .orElseThrow(() -> new ResourceNotFoundException("Transfer", reference));
        if (transfer.getStatus() == TransferStatus.PROCESSING) {
            applyFailure(transfer, "The transfer never reached the payment provider");
        }
    }

    @Transactional(readOnly = true)
    public List<TransferRef> awaitingOutcome(Instant cutoff, int batchSize) {
        List<TransferRef> found = new ArrayList<>();
        for (Transfer transfer : transferRepository.findAwaitingOutcome(
                TransferStatus.PROCESSING, TransferOutbox.Status.DONE, cutoff, PageRequest.of(0, batchSize))) {
            found.add(new TransferRef(transfer.getId(), transfer.getReference()));
        }
        return found;
    }

    /** A transfer's id and our reference for it. */
    public record TransferRef(UUID id, String reference) {
    }

    // ------------------------------------------------------------------------------------------------
    // The three outcomes
    // ------------------------------------------------------------------------------------------------

    private void applySuccess(Transfer transfer, String providerReference) {
        if (transfer.getStatus() == TransferStatus.SUCCESS) {
            return; // already done: the provider (or a webhook) repeated itself
        }
        if (transfer.getStatus().isFinal()) {
            throw new PayBridgeException(ErrorCode.INVALID_STATE, "Transfer " + transfer.getReference()
                    + " is already " + transfer.getStatus() + " but the provider now says it succeeded: MANUAL REVIEW NEEDED");
        }

        Money amount = transfer.amount();
        Money fee = transfer.fee();
        Money total = amount.add(fee);

        List<PostingLine> lines = new ArrayList<>();
        lines.add(new PostingLine(accountApi.systemAccountId(SystemAccount.SUSPENSE), total.negate()));
        lines.add(new PostingLine(accountApi.systemAccountId(SystemAccount.PAYSTACK_SETTLEMENT), amount));
        if (fee.isPositive()) {
            lines.add(new PostingLine(accountApi.systemAccountId(SystemAccount.FEES), fee));
        }
        ledgerApi.post(new PostingRequest(EntryType.EXTERNAL_SETTLEMENT, transfer.getReference(),
                "External transfer paid out", lines));

        if (providerReference != null) {
            transfer.recordProviderReference(providerReference);
        }
        transfer.markSuccess(clock.instant());
        outbox(transfer.getId()).markDone();

        events.publishEvent(new TransferCompleted(transfer.getId(), transfer.getReference(), transfer.getType(),
                transfer.getCustomerId(), transfer.getSourceAccountId(), null,
                amount.minorUnits(), fee.minorUnits(), transfer.getCurrency()));
    }

    private void applyFailure(Transfer transfer, String reason) {
        TransferStatus status = transfer.getStatus();
        if (status == TransferStatus.FAILED || status == TransferStatus.REVERSED) {
            return; // already refunded
        }
        if (status == TransferStatus.SUCCESS) {
            applyReversal(transfer, reason); // it had succeeded, so this is really a reversal
            return;
        }

        PostedEntry hold = ledgerApi.find(EntryType.EXTERNAL_HOLD, transfer.getReference())
                .orElseThrow(() -> new IllegalStateException("No hold entry for transfer " + transfer.getReference()));
        ledgerApi.reverse(hold.id(), "release:" + transfer.getReference(), "Refund: " + reason);
        complianceApi.release(transfer.getCustomerId(), transfer.amount(), transfer.getUsageDate());

        transfer.markFailed(reason, clock.instant());
        outbox(transfer.getId()).markDone();

        events.publishEvent(new TransferFailed(transfer.getId(), transfer.getReference(), transfer.getCustomerId(),
                transfer.getSourceAccountId(), transfer.getAmountMinor(), transfer.getCurrency(), reason));
    }

    private void applyReversal(Transfer transfer, String reason) {
        if (transfer.getStatus() == TransferStatus.REVERSED) {
            return;
        }
        if (transfer.getStatus() != TransferStatus.SUCCESS) {
            throw new PayBridgeException(ErrorCode.INVALID_STATE,
                    "Only a successful transfer can be reversed, but " + transfer.getReference() + " is " + transfer.getStatus());
        }

        PostedEntry settlement = ledgerApi.find(EntryType.EXTERNAL_SETTLEMENT, transfer.getReference())
                .orElseThrow(() -> new IllegalStateException("No settlement entry for " + transfer.getReference()));
        PostedEntry hold = ledgerApi.find(EntryType.EXTERNAL_HOLD, transfer.getReference())
                .orElseThrow(() -> new IllegalStateException("No hold entry for " + transfer.getReference()));
        // undo the payout first (the money returns to suspense), then undo the hold (the customer gets it all back)
        ledgerApi.reverse(settlement.id(), "unsettle:" + transfer.getReference(), "Reversal: " + reason);
        ledgerApi.reverse(hold.id(), "release:" + transfer.getReference(), "Refund: " + reason);
        complianceApi.release(transfer.getCustomerId(), transfer.amount(), transfer.getUsageDate());

        transfer.markReversed(reason, clock.instant());

        events.publishEvent(new TransferReversed(transfer.getId(), transfer.getReference(), transfer.getCustomerId(),
                transfer.getSourceAccountId(), transfer.getAmountMinor(), transfer.getCurrency(), reason));
    }

    private TransferOutbox outbox(UUID transferId) {
        return outboxRepository.findByTransferId(transferId)
                .orElseThrow(() -> new IllegalStateException("No outbox entry for transfer " + transferId));
    }

    private static String naira(long kobo) {
        return "NGN " + String.format("%,d.%02d", kobo / 100, kobo % 100);
    }
}
