package com.academy.paybridge.transfer.service;

import com.academy.paybridge.transfer.client.GatewayTransferResult;
import com.academy.paybridge.transfer.client.GatewayUnavailableException;
import com.academy.paybridge.transfer.client.TransferGateway;
import com.academy.paybridge.transfer.client.TransferInstruction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The postman. Picks up transfers waiting in the outbox and hands them to the payment provider.
 * <p>
 * This class is deliberately NOT transactional. The slow, unreliable call to the provider happens here, with no
 * database transaction open (no locks held, no connection tied up). Each database change is a separate short
 * transaction inside {@link ExternalTransferService}.
 */
@Component
public class TransferDispatcher {

    private static final Logger log = LoggerFactory.getLogger(TransferDispatcher.class);

    private final ExternalTransferService service;
    private final TransferGateway gateway;
    private final int batchSize;

    public TransferDispatcher(ExternalTransferService service,
                              TransferGateway gateway,
                              @Value("${paybridge.dispatcher.batch-size:20}") int batchSize) {
        this.service = service;
        this.gateway = gateway;
        this.batchSize = batchSize;
    }

    /** Sends everything that is due. Returns how many transfers were handled. */
    public int dispatchDue() {
        List<UUID> due = service.dueTransferIds(batchSize);
        int handled = 0;
        for (UUID transferId : due) {
            try {
                dispatch(transferId);
                handled++;
            } catch (RuntimeException e) {
                // one bad transfer must never stop the others
                log.error("Could not dispatch transfer {}", transferId, e);
            }
        }
        return handled;
    }

    /** Sends one transfer. Safe to call more than once for the same transfer. */
    public void dispatch(UUID transferId) {
        Optional<TransferInstruction> instruction = service.beginDispatch(transferId);
        if (instruction.isEmpty()) {
            return;
        }

        GatewayTransferResult result;
        try {
            result = gateway.initiateTransfer(instruction.get());
        } catch (GatewayUnavailableException e) {
            // Unknown outcome: the provider may or may not have it. Never treat this as a failure.
            service.scheduleRetryOrGiveUp(transferId, e.getMessage());
            return;
        }

        String reference = instruction.get().reference();
        switch (result.outcome()) {
            case PENDING -> service.recordAccepted(transferId, result.providerReference());
            case SUCCESS, FAILED -> service.applyProviderResult(
                    reference, result.outcome(), result.providerReference(), result.message());
            case REVERSED, NOT_FOUND -> {
                // not valid answers to "please send this": ask again later rather than guess
                service.scheduleRetryOrGiveUp(transferId, "Unexpected answer from provider: " + result.outcome());
            }
        }
    }
}
