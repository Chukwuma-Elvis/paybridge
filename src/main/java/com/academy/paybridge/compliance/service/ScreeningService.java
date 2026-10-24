package com.academy.paybridge.compliance.service;

import com.academy.paybridge.compliance.api.CaseDecision;
import com.academy.paybridge.compliance.api.CaseStatus;
import com.academy.paybridge.compliance.api.CaseView;
import com.academy.paybridge.compliance.api.Party;
import com.academy.paybridge.compliance.api.ScreeningOutcome;
import com.academy.paybridge.compliance.api.ScreeningResult;
import com.academy.paybridge.compliance.api.WatchlistEntry;
import com.academy.paybridge.shared.exception.ErrorCode;
import com.academy.paybridge.shared.exception.PayBridgeException;
import com.academy.paybridge.shared.exception.ResourceNotFoundException;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import java.sql.Date;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Screening and the case record. Joins the caller's transaction, so a case opened for a payment commits (or rolls back) with it.
 * <p>
 * Nothing here logs a screened party's name or birth date: only the case id and the outcome.
 */
@Component
class ScreeningService {

    private static final Logger log = LoggerFactory.getLogger(ScreeningService.class);

    private static final RowMapper<CaseView> CASE_ROW = (rs, row) -> new CaseView(
            rs.getObject("id", UUID.class),
            rs.getString("context_ref"),
            rs.getString("party_role"),
            rs.getString("party_name"),
            rs.getString("party_country"),
            rs.getObject("party_birth", LocalDate.class),
            ScreeningOutcome.valueOf(rs.getString("outcome")),
            rs.getString("match_details"),
            CaseStatus.valueOf(rs.getString("status")),
            rs.getString("decision_reason"),
            rs.getString("decided_by"),
            rs.getObject("decided_at", OffsetDateTime.class) == null ? null : rs.getObject("decided_at", OffsetDateTime.class).toInstant(),
            rs.getObject("created_at", OffsetDateTime.class).toInstant());

    private final ScreeningProvider provider;
    private final AmlProperties properties;
    private final JdbcTemplate jdbc;
    private final MeterRegistry meters;

    ScreeningService(ScreeningProvider provider, AmlProperties properties, JdbcTemplate jdbc, MeterRegistry meters) {
        this.provider = provider;
        this.properties = properties;
        this.jdbc = jdbc;
        this.meters = meters;
    }

    ScreeningResult screen(Party party, String partyRole, String contextRef) {
        if (party == null || party.fullName() == null || party.fullName().isBlank() || party.fullName().length() > 200) {
            throw new PayBridgeException(ErrorCode.VALIDATION_FAILED, "A party to screen needs a name of up to 200 characters");
        }
        if (party.country() != null && !party.country().matches("[A-Za-z]{2}")) {
            throw new PayBridgeException(ErrorCode.VALIDATION_FAILED, "A country must be a two-letter code");
        }
        if (partyRole == null || partyRole.isBlank() || partyRole.length() > 20 || contextRef == null || contextRef.isBlank()
                || contextRef.length() > 100) {
            throw new PayBridgeException(ErrorCode.VALIDATION_FAILED, "Screening needs a party role and a context reference");
        }

        Optional<CaseView> earlier = findCase(contextRef, partyRole);
        if (earlier.isPresent()) {
            return new ScreeningResult(earlier.get().outcome(), earlier.get().id(), "Already screened: case " + earlier.get().id());
        }

        ScreeningPolicy.Assessment assessment = ScreeningPolicy.assess(party, provider.candidatesFor(party), properties.screening());
        meters.counter("paybridge.screening", "outcome", assessment.outcome().name().toLowerCase(Locale.ROOT)).increment();
        if (assessment.outcome() == ScreeningOutcome.CLEAR) {
            return new ScreeningResult(ScreeningOutcome.CLEAR, null, "No close match on the watch list");
        }

        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into screening_case (id, context_ref, party_role, party_name, party_country, party_birth, outcome,
                                            match_details, status, created_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, 'OPEN', now())
                on conflict (context_ref, party_role) do nothing
                """, id, contextRef, partyRole, party.fullName().trim(),
                party.country() == null ? null : party.country().toUpperCase(Locale.ROOT),
                party.dateOfBirth() == null ? null : Date.valueOf(party.dateOfBirth()),
                assessment.outcome().name(), assessment.details());
        CaseView stored = findCase(contextRef, partyRole).orElseThrow();
        log.warn("Screening {} for {} {}: case {} opened", assessment.outcome(), partyRole, contextRef, stored.id());
        return new ScreeningResult(stored.outcome(), stored.id(), "A " + stored.outcome() + " case was opened");
    }

    CaseView openReviewCase(String contextRef, String partyRole, String partyName, String reason) {
        if (contextRef == null || contextRef.isBlank() || contextRef.length() > 100 || partyRole == null || partyRole.isBlank()
                || partyRole.length() > 20 || partyName == null || partyName.isBlank() || partyName.length() > 200
                || reason == null || reason.isBlank()) {
            throw new PayBridgeException(ErrorCode.VALIDATION_FAILED, "A review case needs a context, a role, a name and a reason");
        }
        jdbc.update("""
                insert into screening_case (id, context_ref, party_role, party_name, outcome, match_details, status, created_at)
                values (?, ?, ?, ?, 'REVIEW', ?, 'OPEN', now())
                on conflict (context_ref, party_role) do nothing
                """, UUID.randomUUID(), contextRef, partyRole, partyName.trim(), reason.substring(0, Math.min(reason.length(), 1000)));
        meters.counter("paybridge.screening", "outcome", "manual_review").increment();
        CaseView stored = findCase(contextRef, partyRole).orElseThrow();
        log.warn("Review case {} opened for {} {}", stored.id(), partyRole, contextRef);
        return stored;
    }

    CaseView getCase(UUID caseId) {
        return jdbc.query("select * from screening_case where id = ?", CASE_ROW, caseId).stream().findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("Screening case", caseId));
    }

    List<CaseView> listCases(CaseStatus status, int limit) {
        int bounded = Math.max(1, Math.min(limit, 200));
        return jdbc.query("select * from screening_case where status = ? order by created_at desc limit ?", CASE_ROW,
                status.name(), bounded);
    }

    CaseView decideCase(UUID caseId, CaseDecision decision, String reason, String decidedBy) {
        if (decision == null) {
            throw new PayBridgeException(ErrorCode.VALIDATION_FAILED, "A decision is required");
        }
        if (reason == null || reason.isBlank()) {
            throw new PayBridgeException(ErrorCode.VALIDATION_FAILED, "A written reason is required to decide a case");
        }
        if (reason.length() > 500) {
            throw new PayBridgeException(ErrorCode.VALIDATION_FAILED, "The reason may be at most 500 characters");
        }
        CaseView current = getCase(caseId);
        if (current.status() != CaseStatus.OPEN) {
            return sameDecisionOrRefuse(current, decision);
        }
        if (current.outcome() == ScreeningOutcome.BLOCK && decision == CaseDecision.APPROVE) {
            throw new PayBridgeException(ErrorCode.INVALID_STATE,
                    "A BLOCK case cannot be approved here: it must be escalated and can only be rejected");
        }
        // one statement that only succeeds while the case is still open, so two simultaneous decisions cannot both win
        int updated = jdbc.update("""
                update screening_case set status = ?, decision_reason = ?, decided_by = ?, decided_at = now()
                where id = ? and status = 'OPEN'
                """, decision.resultingStatus().name(), reason.trim(), decidedBy, caseId);
        CaseView after = getCase(caseId);
        if (updated == 0) {
            return sameDecisionOrRefuse(after, decision);
        }
        log.info("Screening case {} {} by {}", caseId, after.status(), decidedBy);
        meters.counter("paybridge.screening.decisions", "decision", decision.name().toLowerCase(Locale.ROOT)).increment();
        return after;
    }

    private CaseView sameDecisionOrRefuse(CaseView decided, CaseDecision requested) {
        if (decided.status() == requested.resultingStatus()) {
            return decided; // the same decision again: nothing changes
        }
        throw new PayBridgeException(ErrorCode.INVALID_STATE, "Case " + decided.id() + " was already " + decided.status());
    }

    private Optional<CaseView> findCase(String contextRef, String partyRole) {
        return jdbc.query("select * from screening_case where context_ref = ? and party_role = ?", CASE_ROW, contextRef, partyRole)
                .stream().findFirst();
    }

    List<WatchlistEntry> listWatchlist() {
        return provider.allEntries();
    }

    WatchlistEntry addWatchlistEntry(WatchlistEntry entry) {
        return provider.addEntry(entry);
    }

    void resetWatchlist() {
        provider.reset();
    }
}
