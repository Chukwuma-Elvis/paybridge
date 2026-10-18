package com.academy.paybridge.remittance.service;

import com.academy.paybridge.shared.logging.JobCorrelation;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The alarm clock for the remittance engine. Kept apart so tests switch it off ({@code paybridge.remittance.processor.enabled=false})
 * and run the engine one pass at a time.
 */
@Component
@ConditionalOnProperty(name = "paybridge.remittance.processor.enabled", havingValue = "true", matchIfMissing = true)
class RemittanceSchedule {

    private final RemittanceProcessor processor;

    RemittanceSchedule(RemittanceProcessor processor) {
        this.processor = processor;
    }

    @Scheduled(fixedDelayString = "${paybridge.remittance.processor.interval:3s}")
    void run() {
        JobCorrelation.run("remittance", () -> processor.drain(10));
    }
}
