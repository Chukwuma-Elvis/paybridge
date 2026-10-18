package com.academy.paybridge.fx.service;

import com.academy.paybridge.fx.api.Corridor;
import com.academy.paybridge.fx.client.ProviderRate;
import com.academy.paybridge.fx.domain.FxRate;
import com.academy.paybridge.fx.domain.FxSpread;
import com.academy.paybridge.fx.domain.RateStatus;
import com.academy.paybridge.fx.repository.FxRateRepository;
import com.academy.paybridge.fx.repository.FxSpreadRepository;
import com.academy.paybridge.shared.exception.ErrorCode;
import com.academy.paybridge.shared.exception.PayBridgeException;
import com.academy.paybridge.shared.exception.ResourceNotFoundException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Everything that reads or writes the stored rates, one short transaction per call. {@link FxRateService} asks the rate source
 * OUTSIDE any transaction (a slow outside call must never hold a database transaction open) and then hands the answer here.
 */
@Component
@Transactional
class FxRateStore {

    private static final int MAX_SOURCE_LENGTH = 60;
    private static final int MAX_NOTE_LENGTH = 200;

    private final FxRateRepository rates;
    private final FxSpreadRepository spreads;
    private final FxProperties properties;
    private final Clock clock;

    FxRateStore(FxRateRepository rates, FxSpreadRepository spreads, FxProperties properties, Clock clock) {
        this.rates = rates;
        this.spreads = spreads;
        this.properties = properties;
        this.clock = clock;
    }

    /** Applies the rules to a rate the source reported, and stores it as active or held. */
    RefreshOutcome record(Corridor corridor, ProviderRate reported) {
        BigDecimal mid = RatePolicy.normalise(reported.midRate());
        if (!RatePolicy.isPlausible(mid, properties.minRate(), properties.maxRate())) {
            return RefreshOutcome.REJECTED_IMPLAUSIBLE;
        }
        if (RatePolicy.isStale(reported.asOf(), clock.instant(), properties.maxRateAge())) {
            return RefreshOutcome.REJECTED_STALE;
        }

        Optional<FxRate> inForce = rates.findFirstByCorridorAndStatusOrderByCreatedAtDescIdDesc(corridor, RateStatus.ACTIVE);
        if (inForce.isPresent() && RatePolicy.isSpike(inForce.get().getMidRate(), mid, properties.spikeThresholdPercent())) {
            Optional<FxRate> waiting = rates.findFirstByCorridorAndStatusOrderByCreatedAtDescIdDesc(corridor, RateStatus.HELD);
            if (waiting.isPresent() && waiting.get().getMidRate().compareTo(mid) == 0
                    && rates.findByApprovesRateId(waiting.get().getId()).isEmpty()) {
                return RefreshOutcome.HELD_ALREADY;
            }
            rates.save(new FxRate(corridor, mid, shorten(reported.source(), MAX_SOURCE_LENGTH), reported.asOf(), RateStatus.HELD, null,
                    "Moved more than " + properties.spikeThresholdPercent() + "% from " + inForce.get().getMidRate()));
            return RefreshOutcome.HELD;
        }

        rates.save(new FxRate(corridor, mid, shorten(reported.source(), MAX_SOURCE_LENGTH), reported.asOf(), RateStatus.ACTIVE, null, null));
        return RefreshOutcome.ACCEPTED;
    }

    @Transactional(readOnly = true)
    Optional<FxRate> latestActive(Corridor corridor) {
        return rates.findFirstByCorridorAndStatusOrderByCreatedAtDescIdDesc(corridor, RateStatus.ACTIVE);
    }

    /**
     * An admin accepts a held rate. The held row is never changed: approval adds a new ACTIVE row that points back at it.
     * Approving the same held rate again returns the same approval.
     */
    FxRate approve(UUID heldRateId, String approvedBy) {
        FxRate held = rates.findById(heldRateId).orElseThrow(() -> new ResourceNotFoundException("Exchange rate", heldRateId));
        if (held.getStatus() != RateStatus.HELD) {
            throw new PayBridgeException(ErrorCode.INVALID_STATE, "Only a held rate can be approved");
        }
        // "add the approval unless it exists" in one statement, so two simultaneous approvals produce exactly one row
        rates.approveIfAbsent(held.getCorridor().name(), held.getMidRate(), shorten("approved:" + approvedBy, MAX_SOURCE_LENGTH),
                clock.instant(), heldRateId,
                shorten("Approved by " + approvedBy + "; source was " + held.getSource(), MAX_NOTE_LENGTH));
        return rates.findByApprovesRateId(heldRateId)
                .orElseThrow(() -> new IllegalStateException("The approval vanished right after being recorded"));
    }

    /** An admin sets a rate by hand, for example when the source is down. The same believable limits apply. */
    FxRate setManually(Corridor corridor, BigDecimal midRate, String note, String setBy) {
        BigDecimal mid = RatePolicy.normalise(midRate);
        if (!RatePolicy.isPlausible(mid, properties.minRate(), properties.maxRate())) {
            throw new PayBridgeException(ErrorCode.VALIDATION_FAILED, "The rate must be between "
                    + properties.minRate() + " and " + properties.maxRate());
        }
        return rates.save(new FxRate(corridor, mid, shorten("manual:" + setBy, MAX_SOURCE_LENGTH), clock.instant(),
                RateStatus.ACTIVE, null, note == null ? null : shorten(note, MAX_NOTE_LENGTH)));
    }

    @Transactional(readOnly = true)
    int spreadBps(Corridor corridor) {
        return spreads.findFirstByCorridorOrderByCreatedAtDescIdDesc(corridor)
                .map(FxSpread::getSpreadBps)
                .orElse(properties.defaultSpreadBps());
    }

    FxSpread setSpread(Corridor corridor, int spreadBps, String setBy) {
        return spreads.save(new FxSpread(corridor, spreadBps, shorten(setBy, 100)));
    }

    @Transactional(readOnly = true)
    List<FxRate> recent(Corridor corridor, int limit) {
        return rates.findByCorridorOrderByCreatedAtDescIdDesc(corridor, PageRequest.of(0, Math.max(1, Math.min(limit, 200))));
    }

    private static String shorten(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max);
    }
}
