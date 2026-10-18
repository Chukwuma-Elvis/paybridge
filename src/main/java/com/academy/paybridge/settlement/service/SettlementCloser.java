package com.academy.paybridge.settlement.service;

import com.academy.paybridge.shared.logging.JobCorrelation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;

/**
 * The timer that cuts the partners' accounts into batches. Each run closes, for every settled partner, everything older than the
 * configured lag. Running it again straight away finds nothing new and makes nothing. Switch it off with
 * {@code paybridge.settlement.closer.enabled=false} (tests do, and call {@link BatchService#close} themselves).
 */
@Component
@ConditionalOnProperty(name = "paybridge.settlement.closer.enabled", havingValue = "true", matchIfMissing = true)
class SettlementCloser {

    private static final Logger log = LoggerFactory.getLogger(SettlementCloser.class);

    private final BatchService batches;
    private final SettlementProperties properties;
    private final Clock clock;

    SettlementCloser(BatchService batches, SettlementProperties properties, Clock clock) {
        this.batches = batches;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${paybridge.settlement.closer.interval:1h}", initialDelayString = "${paybridge.settlement.closer.interval:1h}")
    void run() {
        JobCorrelation.run("settlement", () -> {
            Instant cutoff = clock.instant().minus(properties.closer().lag());
            for (String partner : properties.partners()) {
                try {
                    batches.close(partner, cutoff).ifPresent(batch ->
                            log.info("Closed settlement batch {} for {}", batch.sequence(), partner));
                } catch (RuntimeException e) {
                    log.error("Could not close a settlement batch for {}", partner, e);   // one partner never blocks another
                }
            }
        });
    }
}
