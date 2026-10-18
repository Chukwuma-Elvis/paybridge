package com.academy.paybridge.fx;

import com.academy.paybridge.AbstractIntegrationTest;
import com.academy.paybridge.fx.api.Corridor;
import com.academy.paybridge.fx.api.CurrentRate;
import com.academy.paybridge.fx.client.FakeRateProvider;
import com.academy.paybridge.fx.domain.FxRate;
import com.academy.paybridge.fx.domain.RateStatus;
import com.academy.paybridge.fx.service.FxRateService;
import com.academy.paybridge.fx.service.RefreshOutcome;
import com.academy.paybridge.shared.exception.ErrorCode;
import com.academy.paybridge.shared.exception.PayBridgeException;
import com.academy.paybridge.shared.exception.ResourceNotFoundException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The rate feed against the real database: acceptance, holding, approval, staleness, outages, immutability and the spread. */
class FxRateIntegrationTest extends AbstractIntegrationTest {

    private static final Corridor CORRIDOR = Corridor.USD_NGN;

    @Autowired FxRateService fx;
    @Autowired FakeRateProvider source;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void startFromAHealthySourceAndAKnownRate() {
        source.reset();
        fx.setManually(CORRIDOR, new BigDecimal("1500"), "test baseline", "test");
        fx.setSpread(CORRIDOR, 100, "test");
    }

    @AfterEach
    void leaveTheSourceHealthy() {
        source.reset();
    }

    private static BigDecimal d(String value) {
        return new BigDecimal(value);
    }

    /**
     * Makes the source report a rate far from the baseline and returns the id of the HELD row that results. Each call uses a rate
     * nobody else has used (the database is shared between tests), so a leftover held row from another test cannot be mistaken for it.
     */
    private UUID hold(String roughRate) {
        BigDecimal unique = d(roughRate).add(BigDecimal.valueOf(ThreadLocalRandom.current().nextInt(1, 900_000), 6));
        source.setMidRate(unique);
        assertThat(fx.refresh(CORRIDOR)).isEqualTo(RefreshOutcome.HELD);
        return jdbc.queryForObject(
                "select id from fx_rate where corridor = 'USD_NGN' and status = 'HELD' order by created_at desc, id desc limit 1", UUID.class);
    }

    private long rowCount() {
        Long count = jdbc.queryForObject("select count(*) from fx_rate where corridor = 'USD_NGN'", Long.class);
        return count == null ? 0 : count;
    }

    // ---------------------------------------------------------------- accepting rates

    @Test
    void aSmallMoveIsAcceptedAndBecomesTheRateInForce() {
        source.setMidRate(d("1520"));

        assertThat(fx.refresh(CORRIDOR)).isEqualTo(RefreshOutcome.ACCEPTED);

        assertThat(fx.currentRate(CORRIDOR).midRate()).isEqualByComparingTo("1520");
    }

    @Test
    void aMoveOfExactlyTheThresholdIsStillAccepted() {
        source.setMidRate(d("1575"));   // exactly +5% of 1500

        assertThat(fx.refresh(CORRIDOR)).isEqualTo(RefreshOutcome.ACCEPTED);
    }

    @Test
    void theFirstEverRateOfACorridorIsAcceptedWhateverItIs() {
        // the baseline already exists in this shared database, so check the rule directly: a rate within the believable limits and
        // within the threshold of the previous one is accepted, and the stored value is the exact decimal reported
        source.setMidRate(d("1500.123456"));

        fx.refresh(CORRIDOR);

        assertThat(fx.currentRate(CORRIDOR).midRate()).isEqualByComparingTo("1500.123456");
    }

    // ---------------------------------------------------------------- spikes are held

    @Test
    void aBigJumpIsHeldAndTheOldRateStaysInForce() {
        UUID heldId = hold("1700");   // +13%

        assertThat(fx.currentRate(CORRIDOR).midRate()).as("the held rate is not used").isEqualByComparingTo("1500");
        FxRate newest = fx.recent(CORRIDOR, 1).get(0);
        assertThat(newest.getId()).isEqualTo(heldId);
        assertThat(newest.getStatus()).isEqualTo(RateStatus.HELD);
        assertThat(newest.getNote()).contains("Moved more than 5%");
    }

    @Test
    void aBigDropIsHeldToo() {
        hold("1300");
    }

    @Test
    void theSameHeldRateReportedAgainIsNotStoredAgain() {
        hold("1700");
        long afterFirst = rowCount();

        assertThat(fx.refresh(CORRIDOR)).isEqualTo(RefreshOutcome.HELD_ALREADY);

        assertThat(rowCount()).isEqualTo(afterFirst);
    }

    @Test
    void anAdminApprovalPutsAHeldRateInForceWithoutChangingTheHeldRow() {
        UUID heldId = hold("1700");
        BigDecimal heldMid = jdbc.queryForObject("select mid_rate from fx_rate where id = ?", BigDecimal.class, heldId);

        FxRate approved = fx.approve(heldId, "supervisor");

        assertThat(approved.getStatus()).isEqualTo(RateStatus.ACTIVE);
        assertThat(approved.getApprovesRateId()).isEqualTo(heldId);
        assertThat(approved.getSource()).contains("supervisor");
        assertThat(fx.currentRate(CORRIDOR).midRate()).isEqualByComparingTo(heldMid);
        assertThat(jdbc.queryForObject("select status from fx_rate where id = ?", String.class, heldId)).isEqualTo("HELD");
    }

    @Test
    void approvingTheSameHeldRateTwiceGivesTheSameApproval() {
        UUID heldId = hold("1700");

        FxRate first = fx.approve(heldId, "supervisor");
        FxRate second = fx.approve(heldId, "someone-else");

        assertThat(second.getId()).isEqualTo(first.getId());
        Long approvals = jdbc.queryForObject("select count(*) from fx_rate where approves_rate_id = ?", Long.class, heldId);
        assertThat(approvals).isEqualTo(1);
    }

    @Test
    void manyAdminsApprovingAtTheSameInstantProduceExactlyOneApproval() throws Exception {
        UUID heldId = hold("1700");
        int admins = 12;
        ExecutorService pool = Executors.newFixedThreadPool(admins);
        CountDownLatch startingGun = new CountDownLatch(1);
        List<Future<UUID>> results = new ArrayList<>();
        for (int i = 0; i < admins; i++) {
            final int who = i;
            results.add(pool.submit(() -> {
                startingGun.await();
                return fx.approve(heldId, "admin-" + who).getId();
            }));
        }
        startingGun.countDown();
        Set<UUID> ids = new HashSet<>();
        for (Future<UUID> result : results) {
            ids.add(result.get(30, TimeUnit.SECONDS));
        }
        pool.shutdown();

        assertThat(ids).hasSize(1);
    }

    @Test
    void onlyAHeldRateCanBeApproved() {
        FxRate active = fx.recent(CORRIDOR, 1).get(0);

        assertThatThrownBy(() -> fx.approve(active.getId(), "supervisor"))
                .isInstanceOf(PayBridgeException.class)
                .extracting(e -> ((PayBridgeException) e).getErrorCode()).isEqualTo(ErrorCode.INVALID_STATE);
    }

    @Test
    void approvingARateThatDoesNotExistIsNotFound() {
        assertThatThrownBy(() -> fx.approve(UUID.randomUUID(), "supervisor")).isInstanceOf(ResourceNotFoundException.class);
    }

    // ---------------------------------------------------------------- bad feeds

    @Test
    void whenTheSourceIsDownTheLastGoodRateStaysInForce() {
        source.setDown(true);

        assertThat(fx.refresh(CORRIDOR)).isEqualTo(RefreshOutcome.SOURCE_DOWN);

        assertThat(fx.currentRate(CORRIDOR).midRate()).isEqualByComparingTo("1500");
    }

    @Test
    void aRateThatIsAlreadyStaleOnArrivalIsRefusedAndCannotReplaceAFresherOne() {
        source.setMidRate(d("1510"));
        source.setReportedAge(Duration.ofMinutes(6));
        long before = rowCount();

        assertThat(fx.refresh(CORRIDOR)).isEqualTo(RefreshOutcome.REJECTED_STALE);

        assertThat(rowCount()).isEqualTo(before);
        assertThat(fx.currentRate(CORRIDOR).midRate()).isEqualByComparingTo("1500");
    }

    @Test
    void ratesOutsideTheBelievableLimitsAreRefused() {
        for (String silly : List.of("10", "99.99", "5000.01", "123456")) {
            source.setMidRate(d(silly));
            assertThat(fx.refresh(CORRIDOR)).as("rate %s", silly).isEqualTo(RefreshOutcome.REJECTED_IMPLAUSIBLE);
        }
        assertThat(fx.currentRate(CORRIDOR).midRate()).isEqualByComparingTo("1500");
    }

    // ---------------------------------------------------------------- staleness of what is stored

    @Test
    void whenTheLatestStoredRateHasGrownOldNoRateIsAvailable() {
        // a rate whose as-of time is ten minutes ago is now the most recent one in force
        jdbc.update("""
                insert into fx_rate (id, corridor, mid_rate, source, retrieved_at, status, created_at)
                values (gen_random_uuid(), 'USD_NGN', 1500, 'old', now() - interval '10 minutes', 'ACTIVE', now())
                """);

        assertThatThrownBy(() -> fx.currentRate(CORRIDOR))
                .isInstanceOf(PayBridgeException.class)
                .hasMessageContaining("too old")
                .extracting(e -> ((PayBridgeException) e).getErrorCode()).isEqualTo(ErrorCode.RATE_UNAVAILABLE);
    }

    // ---------------------------------------------------------------- manual rates

    @Test
    void anAdminCanSetARateByHandAndItIsAttributedToThem() {
        FxRate rate = fx.setManually(CORRIDOR, d("1480.5"), "feed outage", "ops-lead");

        assertThat(rate.getSource()).isEqualTo("manual:ops-lead");
        assertThat(rate.getNote()).isEqualTo("feed outage");
        assertThat(fx.currentRate(CORRIDOR).midRate()).isEqualByComparingTo("1480.5");
    }

    @Test
    void aManualRateOutsideTheBelievableLimitsIsRefused() {
        assertThatThrownBy(() -> fx.setManually(CORRIDOR, d("5000.01"), null, "ops-lead"))
                .isInstanceOf(PayBridgeException.class)
                .extracting(e -> ((PayBridgeException) e).getErrorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThatThrownBy(() -> fx.setManually(CORRIDOR, d("0"), null, "ops-lead")).isInstanceOf(PayBridgeException.class);
    }

    // ---------------------------------------------------------------- the spread

    @Test
    void theCustomerRateIsTheMidRateLessTheDefaultMargin() {
        CurrentRate rate = fx.currentRate(CORRIDOR);

        assertThat(rate.spreadBps()).isEqualTo(100);
        assertThat(rate.customerRate()).isEqualByComparingTo("1485");
    }

    @Test
    void changingTheSpreadAddsARowAndTheLatestWins() {
        fx.setSpread(CORRIDOR, 250, "ops-lead");
        fx.setSpread(CORRIDOR, 175, "ops-lead");

        assertThat(fx.spreadBps(CORRIDOR)).isEqualTo(175);
        assertThat(fx.currentRate(CORRIDOR).customerRate()).isEqualByComparingTo("1473.75");
        Long history = jdbc.queryForObject("select count(*) from fx_spread where corridor = 'USD_NGN'", Long.class);
        assertThat(history).isGreaterThanOrEqualTo(3);
    }

    @Test
    void aZeroSpreadIsAllowed() {
        fx.setSpread(CORRIDOR, 0, "ops-lead");

        assertThat(fx.currentRate(CORRIDOR).customerRate()).isEqualByComparingTo("1500");
    }

    @Test
    void theDatabaseRefusesASpreadAboveTwentyPercent() {
        assertThatThrownBy(() -> fx.setSpread(CORRIDOR, 2001, "ops-lead")).isInstanceOf(DataAccessException.class);
    }

    // ---------------------------------------------------------------- history cannot be changed

    @Test
    void ratesCannotBeUpdatedOrDeleted() {
        UUID id = fx.recent(CORRIDOR, 1).get(0).getId();

        assertThatThrownBy(() -> jdbc.update("update fx_rate set mid_rate = 1 where id = ?", id))
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("delete from fx_rate where id = ?", id))
                .hasMessageContaining("append-only");
    }

    @Test
    void spreadHistoryCannotBeChangedEither() {
        fx.setSpread(CORRIDOR, 120, "ops-lead");

        assertThatThrownBy(() -> jdbc.update("update fx_spread set spread_bps = 0"))
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("delete from fx_spread"))
                .hasMessageContaining("append-only");
    }

    // ---------------------------------------------------------------- listing

    @Test
    void recentRatesComeNewestFirstAndTheLimitIsRespected() {
        fx.setManually(CORRIDOR, d("1501"), null, "a");
        fx.setManually(CORRIDOR, d("1502"), null, "b");
        fx.setManually(CORRIDOR, d("1503"), null, "c");

        List<FxRate> recent = fx.recent(CORRIDOR, 2);

        assertThat(recent).hasSize(2);
        assertThat(recent.get(0).getMidRate()).isEqualByComparingTo("1503");
        assertThat(recent.get(1).getMidRate()).isEqualByComparingTo("1502");
        assertThat(fx.recent(CORRIDOR, 0)).as("a limit below one still returns one").hasSize(1);
    }

    @Test
    void ratesAreStoredAsExactDecimalsNotFloatingPoint() {
        fx.setManually(CORRIDOR, d("1500.100000"), null, "a");

        assertThat(jdbc.queryForObject("select mid_rate::text from fx_rate where corridor = 'USD_NGN' order by created_at desc, id desc limit 1", String.class))
                .isEqualTo("1500.100000");
    }
}
