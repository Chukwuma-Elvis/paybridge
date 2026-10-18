package com.academy.paybridge.settlement.service;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * How many settlement breaks are open right now, by age. Old breaks are the ones to chase, so the age is the label an alert would use.
 */
@Component
class SettlementMetrics {

    SettlementMetrics(MeterRegistry meters, JdbcTemplate jdbc) {
        register(meters, jdbc, "0-1d", "created_at > now() - interval '1 day'");
        register(meters, jdbc, "2-7d", "created_at <= now() - interval '1 day' and created_at > now() - interval '7 days'");
        register(meters, jdbc, ">7d", "created_at <= now() - interval '7 days'");
    }

    private static void register(MeterRegistry meters, JdbcTemplate jdbc, String age, String condition) {
        Gauge.builder("paybridge.settlement.breaks.open", () -> {
                    try {
                        Integer count = jdbc.queryForObject("select count(*) from settlement_break where status = 'OPEN' and " + condition,
                                Integer.class);
                        return count == null ? 0 : count;
                    } catch (RuntimeException e) {
                        return -1;
                    }
                })
                .description("Open settlement breaks by age")
                .tag("age", age)
                .register(meters);
    }
}
