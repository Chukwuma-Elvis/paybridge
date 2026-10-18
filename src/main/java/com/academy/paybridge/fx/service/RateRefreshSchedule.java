package com.academy.paybridge.fx.service;

import com.academy.paybridge.fx.api.Corridor;
import com.academy.paybridge.shared.logging.JobCorrelation;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The alarm clock that fetches fresh rates. Switch it off with {@code paybridge.fx.refresher.enabled=false} (tests do, and drive
 * the refresh by hand so they know exactly what happened).
 */
@Component
@ConditionalOnProperty(name = "paybridge.fx.refresher.enabled", havingValue = "true", matchIfMissing = true)
class RateRefreshSchedule {

    private final FxRateService service;

    RateRefreshSchedule(FxRateService service) {
        this.service = service;
    }

    @Scheduled(fixedDelayString = "${paybridge.fx.refresher.interval:60s}")
    void run() {
        JobCorrelation.run("fx-rates", () -> {
            for (Corridor corridor : Corridor.values()) {
                service.refresh(corridor);
            }
        });
    }
}
