package com.academy.paybridge.compliance.service;

import com.academy.paybridge.compliance.api.ComplianceApi;
import com.academy.paybridge.compliance.api.LimitStatus;
import com.academy.paybridge.customer.api.CustomerApi;
import com.academy.paybridge.customer.api.CustomerSummary;
import com.academy.paybridge.shared.exception.ErrorCode;
import com.academy.paybridge.shared.exception.PayBridgeException;
import com.academy.paybridge.shared.money.Money;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

@Service
@Transactional
public class ComplianceService implements ComplianceApi {

    private static final ZoneId LAGOS = ZoneId.of("Africa/Lagos");

    private final CustomerApi customerApi;
    private final ComplianceProperties properties;
    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;

    public ComplianceService(CustomerApi customerApi,
                             ComplianceProperties properties,
                             JdbcTemplate jdbcTemplate,
                             Clock clock) {
        this.customerApi = customerApi;
        this.properties = properties;
        this.jdbcTemplate = jdbcTemplate;
        this.clock = clock;
    }

    @Override
    public void reserve(UUID customerId, Money amount) {
        CustomerSummary customer = customerApi.getCustomer(customerId);
        ComplianceProperties.Limit limit = limitFor(customer);
        long kobo = amount.minorUnits();

        if (kobo > limit.perTransactionMinor()) {
            throw limitExceeded("A single transfer may not exceed " + naira(limit.perTransactionMinor())
                    + " for a " + customer.kycTier() + " customer");
        }
        if (kobo > limit.dailyMinor()) {
            throw limitExceeded("The daily limit for a " + customer.kycTier() + " customer is "
                    + naira(limit.dailyMinor()));
        }

        // One atomic statement: add to today's total only if the result stays within the daily limit.
        // Two simultaneous transfers cannot both squeeze under the limit, because the database serialises them.
        int updated = jdbcTemplate.update("""
                insert into daily_usage (id, customer_id, usage_date, total_minor)
                values (gen_random_uuid(), ?, ?, ?)
                on conflict (customer_id, usage_date)
                do update set total_minor = daily_usage.total_minor + excluded.total_minor
                where daily_usage.total_minor + excluded.total_minor <= ?
                """, customerId, today(), kobo, limit.dailyMinor());

        if (updated == 0) {
            throw limitExceeded("This transfer would take you over today's limit of " + naira(limit.dailyMinor()));
        }
    }

    @Override
    public void release(UUID customerId, Money amount, LocalDate usageDate) {
        jdbcTemplate.update("""
                update daily_usage set total_minor = greatest(total_minor - ?, 0)
                where customer_id = ? and usage_date = ?
                """, amount.minorUnits(), customerId, usageDate);
    }

    @Override
    public LocalDate today() {
        return LocalDate.now(clock.withZone(LAGOS));
    }

    @Override
    @Transactional(readOnly = true)
    public LimitStatus limitStatus(UUID customerId) {
        CustomerSummary customer = customerApi.getCustomer(customerId);
        ComplianceProperties.Limit limit = limitFor(customer);

        List<Long> used = jdbcTemplate.queryForList(
                "select total_minor from daily_usage where customer_id = ? and usage_date = ?",
                Long.class, customerId, today());
        long usedToday = used.isEmpty() ? 0 : used.get(0);

        return new LimitStatus(customerId, customer.kycTier(),
                limit.perTransactionMinor(), limit.dailyMinor(), usedToday);
    }

    private ComplianceProperties.Limit limitFor(CustomerSummary customer) {
        ComplianceProperties.Limit limit = properties.limits() == null ? null : properties.limits().get(customer.kycTier());
        if (limit == null) {
            throw new IllegalStateException("No transfer limit is configured for KYC tier " + customer.kycTier());
        }
        return limit;
    }

    private static PayBridgeException limitExceeded(String message) {
        return new PayBridgeException(ErrorCode.LIMIT_EXCEEDED, message);
    }

    private static String naira(long kobo) {
        return "NGN " + String.format("%,d.%02d", kobo / 100, kobo % 100);
    }
}
