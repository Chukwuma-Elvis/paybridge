package com.academy.paybridge.compliance.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

/**
 * Looks for patterns no single payment shows: a burst of attempts, or several amounts parked just under the reporting threshold
 * (structuring). It only RAISES a flag; a person decides.
 */
@Component
class VelocityService {

    private final AmlProperties properties;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    VelocityService(AmlProperties properties, JdbcTemplate jdbc, Clock clock) {
        this.properties = properties;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    Optional<String> assess(String subjectKey, String eventRef, long amountMinor) {
        Instant now = clock.instant();
        jdbc.update("""
                insert into velocity_event (id, subject_key, event_ref, amount_minor, created_at)
                values (gen_random_uuid(), ?, ?, ?, ?)
                on conflict (subject_key, event_ref) do nothing
                """, subjectKey, eventRef, amountMinor, Timestamp.from(now));

        AmlProperties.Velocity rules = properties.velocity();

        Integer recent = jdbc.queryForObject(
                "select count(*) from velocity_event where subject_key = ? and created_at > ?", Integer.class,
                subjectKey, Timestamp.from(now.minus(rules.window())));
        if (recent != null && recent >= rules.maxCount()) {
            return Optional.of("VELOCITY: " + recent + " payments within " + rules.window().toHours() + " hours");
        }

        long floor = rules.thresholdMinor() * rules.bandPercent() / 100;
        Integer justUnder = jdbc.queryForObject("""
                select count(*) from velocity_event
                where subject_key = ? and created_at > ? and amount_minor >= ? and amount_minor < ?
                """, Integer.class, subjectKey, Timestamp.from(now.minus(rules.structuringWindow())), floor, rules.thresholdMinor());
        if (justUnder != null && justUnder >= rules.structuringCount()) {
            return Optional.of("STRUCTURING: " + justUnder + " payments just under the reporting threshold within "
                    + rules.structuringWindow().toHours() + " hours");
        }
        return Optional.empty();
    }
}
