package com.academy.paybridge.compliance.service;

import com.academy.paybridge.compliance.api.CaseDecision;
import com.academy.paybridge.compliance.api.CaseStatus;
import com.academy.paybridge.compliance.api.CaseView;
import com.academy.paybridge.compliance.api.ComplianceApi;
import com.academy.paybridge.compliance.api.LimitStatus;
import com.academy.paybridge.compliance.api.Party;
import com.academy.paybridge.compliance.api.ScreeningResult;
import com.academy.paybridge.compliance.api.UsdLimitScope;
import com.academy.paybridge.compliance.api.WatchlistEntry;
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
import java.util.Optional;
import java.util.UUID;

@Service
@Transactional
public class ComplianceService implements ComplianceApi {

    private static final ZoneId LAGOS = ZoneId.of("Africa/Lagos");

    private final CustomerApi customerApi;
    private final ComplianceProperties properties;
    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;
    private final ScreeningService screening;
    private final UsdLimitService usdLimits;
    private final VelocityService velocity;

    public ComplianceService(CustomerApi customerApi,
                             ComplianceProperties properties,
                             JdbcTemplate jdbcTemplate,
                             Clock clock,
                             ScreeningService screening,
                             UsdLimitService usdLimits,
                             VelocityService velocity) {
        this.customerApi = customerApi;
        this.properties = properties;
        this.jdbcTemplate = jdbcTemplate;
        this.clock = clock;
        this.screening = screening;
        this.usdLimits = usdLimits;
        this.velocity = velocity;
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

    // ---- anti-money-laundering: each piece lives in its own class; this front desk only hands the work over

    @Override
    public ScreeningResult screen(Party party, String partyRole, String contextRef) {
        return screening.screen(party, partyRole, contextRef);
    }

    @Override
    public LocalDate reserveUsd(UsdLimitScope scope, String subjectKey, long usdMinor) {
        LocalDate day = today();
        usdLimits.reserve(scope, subjectKey, usdMinor, day);
        return day;
    }

    @Override
    public void releaseUsd(UsdLimitScope scope, String subjectKey, long usdMinor, LocalDate usageDate) {
        usdLimits.release(scope, subjectKey, usdMinor, usageDate);
    }

    @Override
    public Optional<String> assessVelocity(String subjectKey, String eventRef, long usdMinor) {
        return velocity.assess(subjectKey, eventRef, usdMinor);
    }

    @Override
    public CaseView openReviewCase(String contextRef, String partyRole, String partyName, String reason) {
        return screening.openReviewCase(contextRef, partyRole, partyName, reason);
    }

    @Override
    @Transactional(readOnly = true)
    public CaseView getCase(UUID caseId) {
        return screening.getCase(caseId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<CaseView> listCases(CaseStatus status, int limit) {
        return screening.listCases(status, limit);
    }

    @Override
    public CaseView decideCase(UUID caseId, CaseDecision decision, String reason, String decidedBy) {
        return screening.decideCase(caseId, decision, reason, decidedBy);
    }

    @Override
    @Transactional(readOnly = true)
    public List<WatchlistEntry> listWatchlist() {
        return screening.listWatchlist();
    }

    @Override
    public WatchlistEntry addWatchlistEntry(WatchlistEntry entry) {
        return screening.addWatchlistEntry(entry);
    }

    @Override
    public void resetWatchlist() {
        screening.resetWatchlist();
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
