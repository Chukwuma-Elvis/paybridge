package com.academy.paybridge.transfer.service;

import com.academy.paybridge.transfer.client.GatewayOutcome;
import com.academy.paybridge.transfer.client.GatewayTransferResult;
import com.academy.paybridge.transfer.client.GatewayUnavailableException;
import com.academy.paybridge.transfer.client.TransferGateway;
import com.academy.paybridge.transfer.client.TransferInstruction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * The postman and the detective for system payouts, in one class (they are small because {@link SystemPayoutService} does the
 * database work). Like {@link TransferDispatcher} it is deliberately NOT transactional: the provider is slow and unreliable, so it is
 * called with no database transaction open.
 */
@Component
public class SystemPayoutDispatcher {

    private static final Logger log = LoggerFactory.getLogger(SystemPayoutDispatcher.class);

    private final SystemPayoutService service;
    private final TransferGateway gateway;
    private final Clock clock;
    private final int batchSize;
    private final Duration minAge;

    public SystemPayoutDispatcher(SystemPayoutService service, TransferGateway gateway, Clock clock,
                                  @Value("${paybridge.dispatcher.batch-size:20}") int batchSize,
                                  @Value("${paybridge.reconciler.min-age:10s}") Duration minAge) {
        this.service = service;
        this.gateway = gateway;
        this.clock = clock;
        this.batchSize = batchSize;
        this.minAge = minAge;
    }

    /** Sends every payout that is due. Returns how many were handled. */
    public int dispatchDue() {
        int handled = 0;
        for (String reference : service.dueReferences(batchSize)) {
            try {
                dispatch(reference);
                handled++;
            } catch (RuntimeException e) {
                log.error("Could not dispatch payout {}", reference, e);   // one bad payout must never stop the others
            }
        }
        return handled;
    }

    public void dispatch(String reference) {
        Optional<SystemPayoutService.Dispatch> work = service.beginDispatch(reference);
        if (work.isEmpty()) {
            return;
        }
        SystemPayoutService.Dispatch d = work.get();
        GatewayTransferResult result;
        try {
            String recipient = gateway.createRecipient(d.bankCode(), d.accountNumber(), d.accountName());
            result = gateway.initiateTransfer(new TransferInstruction(d.reference(), d.amountMinor(), d.currency(), recipient, d.narration()));
        } catch (GatewayUnavailableException e) {
            service.scheduleRetryOrGiveUp(reference, e.getMessage());   // unknown outcome: never treat as a failure
            return;
        }
        switch (result.outcome()) {
            case PENDING -> service.recordAccepted(reference, result.providerReference());
            case SUCCESS, FAILED -> service.applyProviderResult(reference, result.outcome(), result.providerReference(), result.message());
            case REVERSED, NOT_FOUND -> service.scheduleRetryOrGiveUp(reference, "Unexpected answer from provider: " + result.outcome());
        }
    }

    /** Asks the provider about payouts that have been quiet a while. Returns how many were checked. */
    public int reconcile() {
        Instant cutoff = clock.instant().minus(minAge);
        int checked = 0;
        // every page, not just the first: payouts the provider keeps "pending" must not crowd out the ones that can be settled
        for (int page = 0; page < 1_000; page++) {
            List<String> waiting = service.awaitingOutcome(cutoff, page, batchSize);
            checked += reconcileAll(waiting);
            if (waiting.size() < batchSize) {
                break;
            }
        }
        return checked;
    }

    private int reconcileAll(List<String> waiting) {
        int checked = 0;
        for (String reference : waiting) {
            try {
                GatewayTransferResult result = gateway.fetchTransferStatus(reference);
                if (result.outcome() == GatewayOutcome.NOT_FOUND) {
                    service.failIfNeverSent(reference);
                } else {
                    service.applyProviderResult(reference, result.outcome(), result.providerReference(), result.message());
                }
                checked++;
            } catch (GatewayUnavailableException e) {
                log.warn("Could not check payout {} right now: {}", reference, e.getMessage());
            } catch (RuntimeException e) {
                log.error("Could not reconcile payout {}", reference, e);
            }
        }
        return checked;
    }
}
