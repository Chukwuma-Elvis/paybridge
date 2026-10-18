package com.academy.paybridge.remittance.service;

import com.academy.paybridge.remittance.api.RemittanceStatus;
import com.academy.paybridge.remittance.client.CorridorPartner;
import com.academy.paybridge.remittance.client.PartnerUnavailableException;
import com.academy.paybridge.remittance.repository.RemittanceRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * The engine that keeps remittances moving. It does three jobs on each pass:
 * <ol>
 *   <li><b>Reviews:</b> remittances waiting on a person are re-checked; once the cases are decided they go on or fail.
 *       (Nothing else is done for a remittance under review: no money moves.)</li>
 *   <li><b>Funding check:</b> for remittances still waiting for the partner's confirmation, ask the partner. This is the safety net
 *       for a webhook that never came; a window that ran out fails the remittance.</li>
 *   <li><b>Steps:</b> each funded remittance moves one step (convert, pay out, check the payout, refund).</li>
 * </ol>
 * Like the transfer dispatcher it is deliberately NOT transactional: the partner is asked with no database transaction open, and every
 * database change is a separate short transaction in {@link RemittanceStepper}, {@link FundingService} or {@link ReviewService}.
 */
@Component
public class RemittanceProcessor {

    private static final Logger log = LoggerFactory.getLogger(RemittanceProcessor.class);
    private static final EnumSet<RemittanceStatus> STEPPED = EnumSet.of(RemittanceStatus.FUNDED, RemittanceStatus.CONVERTED,
            RemittanceStatus.PAYOUT_PENDING, RemittanceStatus.REFUND_PENDING);

    private final RemittanceRepository repository;
    private final RemittanceStepper stepper;
    private final ReviewService reviews;
    private final FundingService funding;
    private final CorridorPartner partner;
    private final RemittanceProperties properties;
    private final MeterRegistry meters;
    private final Clock clock;
    private final TransactionTemplate readOnly;

    RemittanceProcessor(RemittanceRepository repository, RemittanceStepper stepper, ReviewService reviews, FundingService funding,
                        CorridorPartner partner, RemittanceProperties properties, MeterRegistry meters, Clock clock,
                        org.springframework.transaction.PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.stepper = stepper;
        this.reviews = reviews;
        this.funding = funding;
        this.partner = partner;
        this.properties = properties;
        this.meters = meters;
        this.clock = clock;
        this.readOnly = new TransactionTemplate(transactionManager);
        this.readOnly.setReadOnly(true);
    }

    /** One pass of all three jobs. Returns how many things were done. */
    public int runOnce() {
        int done = resolveReviews();
        done += checkFunding();
        done += step();
        return done;
    }

    /** Passes repeated until a pass does nothing (or the bound is reached): used by the timer so a funded remittance runs to the end quickly. */
    public int drain(int maxPasses) {
        int total = 0;
        for (int i = 0; i < maxPasses; i++) {
            int done = runOnce();
            total += done;
            if (done == 0) {
                break;
            }
        }
        return total;
    }

    // ---- 1: remittances waiting for a person

    public int resolveReviews() {
        int resolved = 0;
        for (UUID id : ids(RemittanceStatus.UNDER_REVIEW)) {
            try {
                if (reviews.resolve(id)) {
                    resolved++;
                }
            } catch (RuntimeException e) {
                log.error("Could not re-check the review of remittance {}", id, e);
            }
        }
        return resolved;
    }

    // ---- 2: the funding safety net

    public int checkFunding() {
        int acted = 0;
        Instant cutoff = clock.instant().minus(properties.funding().minAge());
        int pageSize = properties.processor().batchSize();
        // page through EVERYTHING that is waiting: looking only at the oldest few would let a pile of quiet old ones starve the rest
        for (int page = 0; page < 1_000; page++) {
            int pageNumber = page;
            List<String> waiting = readOnly.execute(status -> funding.awaitingFundingOlderThan(cutoff, pageNumber, pageSize));
            if (waiting == null || waiting.isEmpty()) {
                break;
            }
            for (String reference : waiting) {
                try {
                    if (checkFundingOf(reference)) {
                        acted++;
                    }
                } catch (PartnerUnavailableException e) {
                    log.warn("Could not ask the partner about remittance {} right now: {}", reference, e.getMessage());
                } catch (RuntimeException e) {
                    log.error("Could not check the funding of remittance {}", reference, e);
                }
            }
            if (waiting.size() < pageSize) {
                break;
            }
        }
        return acted;
    }

    /** Asks the partner about one remittance. True if something changed. */
    boolean checkFundingOf(String reference) {
        String partnerCode = funding.partnerOf(reference).orElse(null);
        if (partnerCode == null) {
            return false;
        }
        CorridorPartner.FundingStatus status = partner.fetchFunding(partnerCode, reference);
        switch (status.state()) {
            case FUNDED -> {
                if (status.amountMinor() == null || status.currency() == null || status.amountMinor() != expectedAmount(reference)
                        || !"USD".equalsIgnoreCase(status.currency())) {
                    funding.flagMismatch(partnerCode, reference, "The partner reports " + status.amountMinor() + " "
                            + status.currency() + " for this remittance");
                    count("mismatch");
                    return true;
                }
                boolean applied = funding.applyFunding(partnerCode, reference, true, status.partnerReference(), status.message())
                        == com.academy.paybridge.transfer.api.PartnerFundingHandler.FundingResult.APPLIED;
                count("funded");
                return applied;
            }
            case FAILED -> {
                funding.applyFunding(partnerCode, reference, false, status.partnerReference(), status.message());
                count("failed");
                return true;
            }
            case UNKNOWN -> {
                funding.expire(reference);
                return false;
            }
        }
        return false;
    }

    private long expectedAmount(String reference) {
        return funding.expectedFunding(funding.partnerOf(reference).orElse(""), reference).map(e -> e.amountMinor()).orElse(-1L);
    }

    // ---- 3: the money steps

    public int step() {
        int handled = 0;
        List<UUID> due = repository.findDueIds(STEPPED, clock.instant(), PageRequest.of(0, properties.processor().batchSize()));
        for (UUID id : due) {
            try {
                RemittanceStatus status = stepper.statusOf(id);
                if (status == null) {
                    continue;
                }
                switch (status) {
                    case FUNDED -> stepper.convert(id);
                    case CONVERTED -> stepper.payout(id);
                    case PAYOUT_PENDING -> stepper.checkPayout(id);
                    case REFUND_PENDING -> stepper.refund(id);
                    default -> {
                        continue;
                    }
                }
                handled++;
            } catch (RuntimeException e) {
                log.error("Step failed for remittance {}: {}", id, e.getMessage());
                try {
                    stepper.recordFailure(id, e.getClass().getSimpleName() + ": " + e.getMessage());
                } catch (RuntimeException inner) {
                    log.error("Could not record the failure of remittance {}", id, inner);
                }
            }
        }
        return handled;
    }

    private List<UUID> ids(RemittanceStatus status) {
        return repository.findIdsByStatus(status, PageRequest.of(0, properties.processor().batchSize()));
    }

    private void count(String result) {
        meters.counter("paybridge.remittance.funding.check", "result", result.toLowerCase(Locale.ROOT)).increment();
    }
}
