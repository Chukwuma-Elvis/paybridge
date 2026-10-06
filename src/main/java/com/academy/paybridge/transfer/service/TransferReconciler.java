package com.academy.paybridge.transfer.service;

import com.academy.paybridge.transfer.client.GatewayOutcome;
import com.academy.paybridge.transfer.client.GatewayTransferResult;
import com.academy.paybridge.transfer.client.GatewayUnavailableException;
import com.academy.paybridge.transfer.client.TransferGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * The detective. For transfers that have been "in progress" a while, asks the provider what really happened and
 * brings our books in line. This is the safety net that resolves every "we do not know" case:
 * <ul>
 *   <li>provider says SUCCESS or FAILED: apply it,</li>
 *   <li>provider says it never heard of the transfer: it was never sent, so refund the customer,</li>
 *   <li>provider says it is still PENDING: leave it and look again next time.</li>
 * </ul>
 * Like the dispatcher, it is not transactional, so no database transaction is open while it waits for the provider.
 */
@Component
public class TransferReconciler {

    private static final Logger log = LoggerFactory.getLogger(TransferReconciler.class);

    private final ExternalTransferService service;
    private final TransferGateway gateway;
    private final Clock clock;
    private final Duration minAge;
    private final int batchSize;

    public TransferReconciler(ExternalTransferService service,
                              TransferGateway gateway,
                              Clock clock,
                              @Value("${paybridge.reconciler.min-age:10s}") Duration minAge,
                              @Value("${paybridge.reconciler.batch-size:50}") int batchSize) {
        this.service = service;
        this.gateway = gateway;
        this.clock = clock;
        this.minAge = minAge;
        this.batchSize = batchSize;
    }

    /** Checks every transfer that has been waiting long enough. Returns how many were checked. */
    public int reconcile() {
        Instant cutoff = clock.instant().minus(minAge);
        List<ExternalTransferService.TransferRef> waiting = service.awaitingOutcome(cutoff, batchSize);
        int checked = 0;

        for (ExternalTransferService.TransferRef transfer : waiting) {
            try {
                reconcileTransfer(transfer.reference());
                checked++;
            } catch (GatewayUnavailableException e) {
                log.warn("Could not check transfer {} right now: {}", transfer.reference(), e.getMessage());
            } catch (RuntimeException e) {
                log.error("Could not reconcile transfer {}", transfer.reference(), e);
            }
        }
        return checked;
    }

    /** Asks the provider about one transfer and applies the answer. */
    public void reconcileTransfer(String reference) {
        GatewayTransferResult result = gateway.fetchTransferStatus(reference);
        if (result.outcome() == GatewayOutcome.NOT_FOUND) {
            service.failIfNeverSent(reference);
        } else {
            service.applyProviderResult(reference, result.outcome(), result.providerReference(), result.message());
        }
    }
}
