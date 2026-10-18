package com.academy.paybridge.compliance.service;

import com.academy.paybridge.compliance.api.UsdLimitScope;
import com.academy.paybridge.shared.exception.ErrorCode;
import com.academy.paybridge.shared.exception.PayBridgeException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Date;
import java.time.LocalDate;

/**
 * Daily and monthly US-dollar allowances per sender and per recipient, counted in one statement per period so that two simultaneous
 * payments cannot both squeeze under the limit (the same approach as the naira daily limit).
 */
@Component
class UsdLimitService {

    private final AmlProperties properties;
    private final JdbcTemplate jdbc;

    UsdLimitService(AmlProperties properties, JdbcTemplate jdbc) {
        this.properties = properties;
        this.jdbc = jdbc;
    }

    void reserve(UsdLimitScope scope, String subjectKey, long usdMinor, LocalDate day) {
        if (usdMinor <= 0) {
            throw new PayBridgeException(ErrorCode.VALIDATION_FAILED, "The amount to count must be positive");
        }
        if (subjectKey == null || subjectKey.isBlank() || subjectKey.length() > 120) {
            throw new PayBridgeException(ErrorCode.VALIDATION_FAILED, "A limit subject key of up to 120 characters is required");
        }
        AmlProperties.Limit limit = scope == UsdLimitScope.SENDER ? properties.usdLimits().sender() : properties.usdLimits().recipient();
        String who = scope == UsdLimitScope.SENDER ? "sender" : "recipient";

        if (usdMinor > limit.perTransactionMinor()) {
            throw exceeded("A single payment may not exceed " + usd(limit.perTransactionMinor()) + " for a " + who);
        }
        if (usdMinor > limit.dailyMinor() || usdMinor > limit.monthlyMinor()) {
            throw exceeded("This amount is larger than the " + who + "'s allowance");
        }
        if (!add(scope, subjectKey, 'D', day, usdMinor, limit.dailyMinor())) {
            throw exceeded("This would take the " + who + " over today's limit of " + usd(limit.dailyMinor()));
        }
        if (!add(scope, subjectKey, 'M', day.withDayOfMonth(1), usdMinor, limit.monthlyMinor())) {
            subtract(scope, subjectKey, 'D', day, usdMinor);   // put back what was just counted
            throw exceeded("This would take the " + who + " over this month's limit of " + usd(limit.monthlyMinor()));
        }
    }

    void release(UsdLimitScope scope, String subjectKey, long usdMinor, LocalDate day) {
        subtract(scope, subjectKey, 'D', day, usdMinor);
        subtract(scope, subjectKey, 'M', day.withDayOfMonth(1), usdMinor);
    }

    private boolean add(UsdLimitScope scope, String subject, char kind, LocalDate start, long amount, long ceiling) {
        return jdbc.update("""
                insert into usd_usage (id, scope, subject_key, period_kind, period_start, total_minor)
                values (gen_random_uuid(), ?, ?, ?, ?, ?)
                on conflict (scope, subject_key, period_kind, period_start)
                do update set total_minor = usd_usage.total_minor + excluded.total_minor
                where usd_usage.total_minor + excluded.total_minor <= ?
                """, scope.name(), subject, String.valueOf(kind), Date.valueOf(start), amount, ceiling) == 1;
    }

    private void subtract(UsdLimitScope scope, String subject, char kind, LocalDate start, long amount) {
        jdbc.update("""
                update usd_usage set total_minor = greatest(total_minor - ?, 0)
                where scope = ? and subject_key = ? and period_kind = ? and period_start = ?
                """, amount, scope.name(), subject, String.valueOf(kind), Date.valueOf(start));
    }

    private static PayBridgeException exceeded(String message) {
        return new PayBridgeException(ErrorCode.LIMIT_EXCEEDED, message);
    }

    private static String usd(long cents) {
        return "USD " + String.format("%,d.%02d", cents / 100, cents % 100);
    }
}
