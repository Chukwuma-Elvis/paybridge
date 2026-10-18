package com.academy.paybridge.fx.service;

import com.academy.paybridge.fx.api.Corridor;
import com.academy.paybridge.fx.api.CurrentRate;
import com.academy.paybridge.fx.client.ProviderRate;
import com.academy.paybridge.fx.client.RateProvider;
import com.academy.paybridge.fx.client.RateProviderUnavailableException;
import com.academy.paybridge.fx.domain.FxRate;
import com.academy.paybridge.fx.domain.FxSpread;
import com.academy.paybridge.shared.exception.ErrorCode;
import com.academy.paybridge.shared.exception.PayBridgeException;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Keeps the exchange rates current and answers "what is the rate right now?".
 * <p>
 * Deliberately NOT {@code @Transactional}: it asks the rate source (an outside call) with no database transaction open, then hands
 * the answer to {@link FxRateStore}, which stores it in its own short transaction.
 */
@Service
public class FxRateService {

    private static final Logger log = LoggerFactory.getLogger(FxRateService.class);

    private final RateProvider provider;
    private final FxRateStore store;
    private final FxProperties properties;
    private final MeterRegistry meters;
    private final Clock clock;

    FxRateService(RateProvider provider, FxRateStore store, FxProperties properties, MeterRegistry meters, Clock clock) {
        this.provider = provider;
        this.store = store;
        this.properties = properties;
        this.meters = meters;
        this.clock = clock;
        for (Corridor corridor : Corridor.values()) {
            Gauge.builder("paybridge.fx.rate.age.seconds", () -> ageSeconds(corridor))
                    .description("How old the rate in force is; -1 when there is none")
                    .tag("corridor", corridor.name())
                    .register(meters);
        }
    }

    public CurrentRate currentRate(Corridor corridor) {
        FxRate inForce = store.latestActive(corridor).orElseThrow(() ->
                new PayBridgeException(ErrorCode.RATE_UNAVAILABLE, "No exchange rate is available for " + corridor));
        if (RatePolicy.isStale(inForce.getRetrievedAt(), clock.instant(), properties.maxRateAge())) {
            throw new PayBridgeException(ErrorCode.RATE_UNAVAILABLE,
                    "The latest exchange rate for " + corridor + " is too old to use");
        }
        int spreadBps = store.spreadBps(corridor);
        return new CurrentRate(corridor, inForce.getMidRate(), RatePolicy.customerRate(inForce.getMidRate(), spreadBps),
                spreadBps, inForce.getSource(), inForce.getRetrievedAt());
    }

    /** Asks the rate source for the corridor's rate and stores it according to the rules. */
    public RefreshOutcome refresh(Corridor corridor) {
        RefreshOutcome outcome;
        try {
            ProviderRate reported = provider.fetch(corridor);
            outcome = store.record(corridor, reported);
        } catch (RateProviderUnavailableException e) {
            outcome = RefreshOutcome.SOURCE_DOWN;
            log.warn("The exchange-rate source gave no answer for {}: {}", corridor, e.getMessage());
        }
        meters.counter("paybridge.fx.refresh", "result", outcome.name().toLowerCase(Locale.ROOT)).increment();
        if (outcome == RefreshOutcome.HELD) {
            log.error("A new {} rate moved too far and is HELD until an admin approves it", corridor);
        } else if (outcome == RefreshOutcome.REJECTED_IMPLAUSIBLE || outcome == RefreshOutcome.REJECTED_STALE) {
            log.warn("The {} rate from the source was refused: {}", corridor, outcome);
        }
        return outcome;
    }

    public FxRate approve(UUID heldRateId, String approvedBy) {
        FxRate approved = store.approve(heldRateId, approvedBy);
        log.info("Held exchange rate {} approved by {}", heldRateId, approvedBy);
        return approved;
    }

    public FxRate setManually(Corridor corridor, BigDecimal midRate, String note, String setBy) {
        FxRate rate = store.setManually(corridor, midRate, note, setBy);
        log.info("Exchange rate for {} set by hand by {}", corridor, setBy);
        return rate;
    }

    public FxSpread setSpread(Corridor corridor, int spreadBps, String setBy) {
        return store.setSpread(corridor, spreadBps, setBy);
    }

    public int spreadBps(Corridor corridor) {
        return store.spreadBps(corridor);
    }

    public List<FxRate> recent(Corridor corridor, int limit) {
        return store.recent(corridor, limit);
    }

    private double ageSeconds(Corridor corridor) {
        try {
            return store.latestActive(corridor)
                    .map(rate -> (double) Duration.between(rate.getRetrievedAt(), Instant.now(clock)).toSeconds())
                    .orElse(-1.0);
        } catch (RuntimeException e) {
            return -1.0;
        }
    }
}
