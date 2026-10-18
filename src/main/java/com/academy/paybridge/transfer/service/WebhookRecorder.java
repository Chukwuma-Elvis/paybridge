package com.academy.paybridge.transfer.service;

import com.academy.paybridge.transfer.api.TransferStatus;
import com.academy.paybridge.transfer.api.TransferType;
import com.academy.paybridge.transfer.client.ProviderEvent;
import com.academy.paybridge.transfer.domain.WebhookEvent;
import com.academy.paybridge.transfer.domain.WebhookStatus;
import com.academy.paybridge.transfer.repository.TransferRepository;
import com.academy.paybridge.transfer.repository.WebhookEventRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/**
 * The bookkeeping around a webhook, one short transaction per call.
 * <p>
 * It is a separate bean from {@link WebhookService} on purpose. The service applies the outcome to the transfer, and if that fails
 * its transaction rolls back. The record of "we received this event, and it failed" must survive that rollback, so it is written
 * here, in its own transactions, before and after.
 */
@Component
@Transactional
class WebhookRecorder {

    /** An event claimed this long ago and never finished was abandoned by a crash, so a repeat delivery may take it over. */
    private static final Duration STALE_AFTER = Duration.ofMinutes(2);

    private final WebhookEventRepository events;
    private final TransferRepository transfers;
    private final Clock clock;

    WebhookRecorder(WebhookEventRepository events, TransferRepository transfers, Clock clock) {
        this.events = events;
        this.transfers = transfers;
        this.clock = clock;
    }

    /** The answer to "may I work on this event?" */
    record Claim(UUID eventRowId, Kind kind) {

        enum Kind {
            /** Yes: it is new, or a previous try failed or was abandoned. */
            OWNED,
            /** No: it was already handled. */
            DUPLICATE,
            /** No: somebody else is handling it right now. */
            IN_PROGRESS
        }
    }

    /** The facts about one of our transfers that a webhook is checked against. */
    record TransferFacts(long amountMinor, String currency, TransferType type, TransferStatus status) {
    }

    /**
     * Writes down that this event arrived and decides who works on it. The insert is {@code ON CONFLICT DO NOTHING}, so when
     * the same event arrives twice at once exactly one caller is told {@code OWNED}.
     */
    Claim claim(String provider, ProviderEvent event, String payloadSha256) {
        int inserted = events.claim(provider, event.eventId(), event.eventType(), event.reference(),
                event.outcome() == null ? null : event.outcome().name(), event.amountMinor(), payloadSha256);
        WebhookEvent row = events.findByProviderAndEventId(provider, event.eventId())
                .orElseThrow(() -> new IllegalStateException("Webhook event vanished right after being recorded"));

        if (inserted == 1) {
            return new Claim(row.getId(), Claim.Kind.OWNED);
        }
        if (row.getStatus().isFinal()) {
            return new Claim(row.getId(), Claim.Kind.DUPLICATE);
        }
        boolean takenOver = events.retake(row.getId(), clock.instant().minus(STALE_AFTER)) == 1;
        return new Claim(row.getId(), takenOver ? Claim.Kind.OWNED : Claim.Kind.IN_PROGRESS);
    }

    @Transactional(readOnly = true)
    Optional<TransferFacts> transferFacts(String reference) {
        return transfers.findByReference(reference)
                .map(transfer -> new TransferFacts(transfer.getAmountMinor(), transfer.getCurrency(),
                        transfer.getType(), transfer.getStatus()));
    }

    /** Records where the event ended up. */
    void finish(UUID eventRowId, WebhookStatus status, String detail) {
        events.findById(eventRowId).ifPresent(row -> row.finish(status, detail));
    }
}
