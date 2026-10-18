package com.academy.paybridge.compliance;

import com.academy.paybridge.AbstractIntegrationTest;
import com.academy.paybridge.compliance.api.CaseDecision;
import com.academy.paybridge.compliance.api.CaseStatus;
import com.academy.paybridge.compliance.api.CaseView;
import com.academy.paybridge.compliance.api.ComplianceApi;
import com.academy.paybridge.compliance.api.Party;
import com.academy.paybridge.compliance.api.ScreeningOutcome;
import com.academy.paybridge.compliance.api.ScreeningResult;
import com.academy.paybridge.compliance.api.UsdLimitScope;
import com.academy.paybridge.shared.exception.ErrorCode;
import com.academy.paybridge.shared.exception.PayBridgeException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Screening cases, USD limits and velocity, against a real database. */
class AmlComplianceTest extends AbstractIntegrationTest {

    @Autowired ComplianceApi compliance;
    @Autowired JdbcTemplate jdbc;

    private static String ref() {
        return "CTX-" + UUID.randomUUID();
    }

    private static String subject() {
        return "subject-" + UUID.randomUUID();
    }

    private ScreeningResult reviewCase(String ref) {
        return compliance.screen(new Party("Alina Samplova", "NG", null), "SENDER", ref);
    }

    private static void assertCode(Runnable action, ErrorCode expected) {
        assertThatThrownBy(action::run).isInstanceOf(PayBridgeException.class)
                .extracting(e -> ((PayBridgeException) e).getErrorCode()).isEqualTo(expected);
    }

    // ---- screening and cases

    @Test
    void aClearPartyOpensNoCase() {
        ScreeningResult result = compliance.screen(new Party("Chidi Okafor", "NG", null), "SENDER", ref());

        assertThat(result.outcome()).isEqualTo(ScreeningOutcome.CLEAR);
        assertThat(result.caseId()).isNull();
    }

    @Test
    void aReviewOpensAnOpenCaseThatNamesTheContext() {
        String ref = ref();

        ScreeningResult result = reviewCase(ref);

        assertThat(result.outcome()).isEqualTo(ScreeningOutcome.REVIEW);
        CaseView stored = compliance.getCase(result.caseId());
        assertThat(stored.status()).isEqualTo(CaseStatus.OPEN);
        assertThat(stored.contextRef()).isEqualTo(ref);
        assertThat(stored.matchDetails()).contains("SYN-002");
    }

    @Test
    void screeningTheSameContextTwiceReturnsTheSameCase() {
        String ref = ref();

        ScreeningResult first = reviewCase(ref);
        ScreeningResult second = reviewCase(ref);

        assertThat(second.caseId()).isEqualTo(first.caseId());
        assertThat(jdbc.queryForObject("select count(*) from screening_case where context_ref = ?", Integer.class, ref)).isEqualTo(1);
    }

    @Test
    void aCaseAppearsInTheOpenListUntilDecided() {
        UUID id = reviewCase(ref()).caseId();
        assertThat(compliance.listCases(CaseStatus.OPEN, 200)).extracting(CaseView::id).contains(id);

        compliance.decideCase(id, CaseDecision.APPROVE, "Different person: dates of birth differ", "reviewer");

        assertThat(compliance.listCases(CaseStatus.OPEN, 200)).extracting(CaseView::id).doesNotContain(id);
        assertThat(compliance.listCases(CaseStatus.APPROVED, 200)).extracting(CaseView::id).contains(id);
    }

    @Test
    void aDecisionNeedsAWrittenReason() {
        UUID id = reviewCase(ref()).caseId();

        assertCode(() -> compliance.decideCase(id, CaseDecision.APPROVE, "  ", "reviewer"), ErrorCode.VALIDATION_FAILED);
        assertCode(() -> compliance.decideCase(id, CaseDecision.APPROVE, null, "reviewer"), ErrorCode.VALIDATION_FAILED);
        assertThat(compliance.getCase(id).status()).isEqualTo(CaseStatus.OPEN);
    }

    @Test
    void theDecisionRecordsWhoWhenAndWhy() {
        UUID id = reviewCase(ref()).caseId();

        CaseView decided = compliance.decideCase(id, CaseDecision.REJECT, "Matches the listed person", "reviewer-1");

        assertThat(decided.status()).isEqualTo(CaseStatus.REJECTED);
        assertThat(decided.decidedBy()).isEqualTo("reviewer-1");
        assertThat(decided.decisionReason()).isEqualTo("Matches the listed person");
        assertThat(decided.decidedAt()).isNotNull();
    }

    @Test
    void repeatingTheSameDecisionIsANoOpAndTheOppositeIsRefused() {
        UUID id = reviewCase(ref()).caseId();
        CaseView first = compliance.decideCase(id, CaseDecision.APPROVE, "Cleared after checking", "reviewer");

        CaseView again = compliance.decideCase(id, CaseDecision.APPROVE, "Another reason", "someone-else");

        assertThat(again.decidedBy()).isEqualTo(first.decidedBy());
        assertThat(again.decisionReason()).isEqualTo(first.decisionReason());
        assertCode(() -> compliance.decideCase(id, CaseDecision.REJECT, "Changed my mind", "reviewer"), ErrorCode.INVALID_STATE);
    }

    @Test
    void aBlockCaseCanOnlyBeRejected() {
        UUID id = compliance.screen(new Party("Zorgon Testlist", "ZZ", LocalDate.of(1970, 1, 1)), "RECIPIENT", ref()).caseId();

        assertCode(() -> compliance.decideCase(id, CaseDecision.APPROVE, "Please let it through", "reviewer"), ErrorCode.INVALID_STATE);
        assertThat(compliance.decideCase(id, CaseDecision.REJECT, "Listed person", "reviewer").status()).isEqualTo(CaseStatus.REJECTED);
    }

    @Test
    void whenTwoReviewersDecideAtOnceExactlyOneDecisionIsRecorded() throws Exception {
        UUID id = reviewCase(ref()).caseId();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch gun = new CountDownLatch(1);
        List<Future<String>> results = new ArrayList<>();
        for (CaseDecision decision : CaseDecision.values()) {
            results.add(pool.submit(() -> {
                gun.await();
                try {
                    return compliance.decideCase(id, decision, "My decision", "r-" + decision).status().name();
                } catch (PayBridgeException e) {
                    return "REFUSED";
                }
            }));
        }
        gun.countDown();
        List<String> outcomes = new ArrayList<>();
        for (Future<String> f : results) {
            outcomes.add(f.get());
        }
        pool.shutdown();

        assertThat(outcomes).containsOnlyOnce("REFUSED");
        assertThat(compliance.getCase(id).status()).isNotEqualTo(CaseStatus.OPEN);
    }

    @Test
    void casesCannotBeDeletedOrRewrittenAfterADecision() {
        UUID id = reviewCase(ref()).caseId();
        compliance.decideCase(id, CaseDecision.APPROVE, "Cleared", "reviewer");

        assertThatThrownBy(() -> jdbc.update("delete from screening_case where id = ?", id)).hasMessageContaining("never deleted");
        assertThatThrownBy(() -> jdbc.update("update screening_case set decision_reason = 'edited' where id = ?", id))
                .hasMessageContaining("never changes");
    }

    @Test
    void anUnknownCaseIsNotFound() {
        assertThatThrownBy(() -> compliance.getCase(UUID.randomUUID())).hasMessageContaining("not found");
    }

    @Test
    void badInputToScreeningIsRefused() {
        assertCode(() -> compliance.screen(new Party(" ", "NG", null), "SENDER", ref()), ErrorCode.VALIDATION_FAILED);
        assertCode(() -> compliance.screen(new Party("Chidi", "NGA", null), "SENDER", ref()), ErrorCode.VALIDATION_FAILED);
        assertCode(() -> compliance.screen(new Party("Chidi", "NG", null), "SENDER", " "), ErrorCode.VALIDATION_FAILED);
    }

    // ---- USD limits

    @Test
    void aPaymentOverThePerTransactionLimitIsRefused() {
        assertCode(() -> compliance.reserveUsd(UsdLimitScope.SENDER, subject(), 500_001), ErrorCode.LIMIT_EXCEEDED);
    }

    @Test
    void theDailyLimitStopsTheThirdPayment() {
        String who = subject();
        compliance.reserveUsd(UsdLimitScope.SENDER, who, 500_000);
        compliance.reserveUsd(UsdLimitScope.SENDER, who, 500_000);

        assertCode(() -> compliance.reserveUsd(UsdLimitScope.SENDER, who, 1), ErrorCode.LIMIT_EXCEEDED);
    }

    @Test
    void senderAndRecipientAllowancesAreSeparate() {
        String who = subject();
        compliance.reserveUsd(UsdLimitScope.SENDER, who, 500_000);
        compliance.reserveUsd(UsdLimitScope.SENDER, who, 500_000);

        compliance.reserveUsd(UsdLimitScope.RECIPIENT, who, 500_000);   // does not throw
    }

    @Test
    void releasingGivesTheAllowanceBack() {
        String who = subject();
        LocalDate day = compliance.reserveUsd(UsdLimitScope.SENDER, who, 500_000);
        compliance.reserveUsd(UsdLimitScope.SENDER, who, 500_000);

        compliance.releaseUsd(UsdLimitScope.SENDER, who, 500_000, day);

        compliance.reserveUsd(UsdLimitScope.SENDER, who, 500_000);       // fits again
        assertCode(() -> compliance.reserveUsd(UsdLimitScope.SENDER, who, 1), ErrorCode.LIMIT_EXCEEDED);
    }

    @Test
    void aMonthlyLimitHitDoesNotLeaveTheDailyCountBehind() {
        String who = subject();
        LocalDate day = compliance.today();
        // 9 earlier days of USD 5,000 each fill USD 45,000 of the USD 50,000 month, without touching today
        for (int i = 1; i <= 9; i++) {
            insertUsage("SENDER", who, 'D', day.minusDays(i + 20L), 500_000);
        }
        insertUsage("SENDER", who, 'M', day.withDayOfMonth(1), 4_800_000);

        assertCode(() -> compliance.reserveUsd(UsdLimitScope.SENDER, who, 300_000), ErrorCode.LIMIT_EXCEEDED);

        Long today = jdbc.queryForObject("select coalesce(sum(total_minor),0) from usd_usage where scope='SENDER' and subject_key=? "
                + "and period_kind='D' and period_start=?", Long.class, who, java.sql.Date.valueOf(day));
        assertThat(today).as("today's count was put back").isZero();
    }

    private void insertUsage(String scope, String subject, char kind, LocalDate start, long total) {
        jdbc.update("insert into usd_usage (id, scope, subject_key, period_kind, period_start, total_minor) "
                + "values (gen_random_uuid(), ?, ?, ?, ?, ?) on conflict do nothing", scope, subject, String.valueOf(kind),
                java.sql.Date.valueOf(start), total);
    }

    @Test
    void whenManyPaymentsRaceForTheLastOfTheAllowanceOnlyTheRightNumberPass() throws Exception {
        String who = subject();
        int threads = 12;                                  // 12 x USD 1,000 against a USD 10,000 day: exactly 10 may pass
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch gun = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            results.add(pool.submit(() -> {
                gun.await();
                try {
                    compliance.reserveUsd(UsdLimitScope.SENDER, who, 100_000);
                    return true;
                } catch (PayBridgeException e) {
                    return false;
                }
            }));
        }
        gun.countDown();
        int passed = 0;
        for (Future<Boolean> f : results) {
            if (f.get()) {
                passed++;
            }
        }
        pool.shutdown();

        assertThat(passed).isEqualTo(10);
        Long counted = jdbc.queryForObject("select total_minor from usd_usage where scope='SENDER' and subject_key=? and period_kind='D'",
                Long.class, who);
        assertThat(counted).isEqualTo(1_000_000L);
    }

    @Test
    void anUnusableAmountOrKeyIsRefused() {
        assertCode(() -> compliance.reserveUsd(UsdLimitScope.SENDER, subject(), 0), ErrorCode.VALIDATION_FAILED);
        assertCode(() -> compliance.reserveUsd(UsdLimitScope.SENDER, " ", 100), ErrorCode.VALIDATION_FAILED);
    }

    // ---- velocity and structuring

    @Test
    void ordinaryActivityRaisesNoFlag() {
        String who = subject();
        assertThat(compliance.assessVelocity(who, ref(), 20_000)).isEmpty();
        assertThat(compliance.assessVelocity(who, ref(), 25_000)).isEmpty();
    }

    @Test
    void aBurstOfPaymentsRaisesAVelocityFlag() {
        String who = subject();
        for (int i = 0; i < 4; i++) {
            assertThat(compliance.assessVelocity(who, ref(), 10_000)).isEmpty();
        }

        assertThat(compliance.assessVelocity(who, ref(), 10_000)).hasValueSatisfying(reason -> assertThat(reason).startsWith("VELOCITY"));
    }

    @Test
    void severalPaymentsJustUnderTheThresholdRaiseAStructuringFlag() {
        String who = subject();
        assertThat(compliance.assessVelocity(who, ref(), 295_000)).isEmpty();
        assertThat(compliance.assessVelocity(who, ref(), 290_000)).isEmpty();

        assertThat(compliance.assessVelocity(who, ref(), 299_900)).hasValueSatisfying(reason -> assertThat(reason).startsWith("STRUCTURING"));
    }

    @Test
    void paymentsWellUnderOrOverTheBandAreNotStructuring() {
        String who = subject();
        assertThat(compliance.assessVelocity(who, ref(), 100_000)).isEmpty();
        assertThat(compliance.assessVelocity(who, ref(), 150_000)).isEmpty();
        assertThat(compliance.assessVelocity(who, ref(), 300_000)).isEmpty();   // at the threshold is not "just under"
    }

    @Test
    void recordingTheSameEventTwiceCountsItOnce() {
        String who = subject();
        String event = ref();
        for (int i = 0; i < 10; i++) {
            assertThat(compliance.assessVelocity(who, event, 10_000)).isEmpty();
        }
        assertThat(jdbc.queryForObject("select count(*) from velocity_event where subject_key = ?", Integer.class, who)).isEqualTo(1);
    }
}
