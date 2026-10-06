package com.academy.paybridge.compliance;

import com.academy.paybridge.AbstractIntegrationTest;
import com.academy.paybridge.compliance.api.ComplianceApi;
import com.academy.paybridge.compliance.api.LimitStatus;
import com.academy.paybridge.shared.exception.ErrorCode;
import com.academy.paybridge.shared.exception.PayBridgeException;
import com.academy.paybridge.shared.money.Money;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * UNVERIFIED limits (application.yml): NGN 50,000 per transfer = 5,000,000 kobo, NGN 100,000 per day = 10,000,000 kobo.
 */
class ComplianceTest extends AbstractIntegrationTest {

    private static final long FIFTY_THOUSAND_NAIRA = 5_000_000;

    @Autowired
    ComplianceApi complianceApi;

    private static void assertLimitExceeded(Throwable thrown) {
        assertThat(thrown).isInstanceOfSatisfying(PayBridgeException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.LIMIT_EXCEEDED));
    }

    @Test
    void anAllowedAmountIsCountedTowardsToday() throws Exception {
        UUID customer = createCustomer();

        complianceApi.reserve(customer, Money.ngn(1_000_000));
        complianceApi.reserve(customer, Money.ngn(2_000_000));

        LimitStatus status = complianceApi.limitStatus(customer);
        assertThat(status.usedTodayMinor()).isEqualTo(3_000_000);
        assertThat(status.remainingTodayMinor()).isEqualTo(7_000_000);
    }

    @Test
    void aSingleTransferOverThePerTransactionLimitIsRefused() throws Exception {
        UUID customer = createCustomer();

        assertThatThrownBy(() -> complianceApi.reserve(customer, Money.ngn(FIFTY_THOUSAND_NAIRA + 1)))
                .satisfies(ComplianceTest::assertLimitExceeded)
                .hasMessageContaining("single transfer");
        assertThat(complianceApi.limitStatus(customer).usedTodayMinor()).isZero();
    }

    @Test
    void theExactPerTransactionLimitIsAllowed() throws Exception {
        UUID customer = createCustomer();

        complianceApi.reserve(customer, Money.ngn(FIFTY_THOUSAND_NAIRA));

        assertThat(complianceApi.limitStatus(customer).usedTodayMinor()).isEqualTo(FIFTY_THOUSAND_NAIRA);
    }

    @Test
    void theDailyLimitStopsTheTransferThatWouldGoOverIt() throws Exception {
        UUID customer = createCustomer();
        complianceApi.reserve(customer, Money.ngn(FIFTY_THOUSAND_NAIRA));
        complianceApi.reserve(customer, Money.ngn(FIFTY_THOUSAND_NAIRA)); // now exactly at 100,000

        assertThatThrownBy(() -> complianceApi.reserve(customer, Money.ngn(1)))
                .satisfies(ComplianceTest::assertLimitExceeded)
                .hasMessageContaining("today's limit");
        assertThat(complianceApi.limitStatus(customer).usedTodayMinor()).isEqualTo(10_000_000);
    }

    @Test
    void releasingAReservationGivesTheAllowanceBack() throws Exception {
        UUID customer = createCustomer();
        complianceApi.reserve(customer, Money.ngn(4_000_000));

        complianceApi.release(customer, Money.ngn(4_000_000), complianceApi.today());

        assertThat(complianceApi.limitStatus(customer).usedTodayMinor()).isZero();
    }

    @Test
    void releaseNeverGoesBelowZero() throws Exception {
        UUID customer = createCustomer();
        complianceApi.reserve(customer, Money.ngn(1_000));

        complianceApi.release(customer, Money.ngn(999_999), complianceApi.today());

        assertThat(complianceApi.limitStatus(customer).usedTodayMinor()).isZero();
    }

    @Test
    void aHigherKycTierRaisesTheLimits() throws Exception {
        UUID customer = createCustomer();
        Money bigTransfer = Money.ngn(15_000_000); // NGN 150,000: over UNVERIFIED, fine for BASIC

        assertThatThrownBy(() -> complianceApi.reserve(customer, bigTransfer))
                .satisfies(ComplianceTest::assertLimitExceeded);

        mockMvc.perform(patch("/api/customers/" + customer + "/kyc-tier")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"kycTier\": \"BASIC\"}"));

        complianceApi.reserve(customer, bigTransfer);
        assertThat(complianceApi.limitStatus(customer).perTransactionMinor()).isEqualTo(20_000_000);
    }

    @Test
    void simultaneousTransfersCannotSqueezeUnderTheDailyLimitTogether() throws Exception {
        UUID customer = createCustomer();
        int attempts = 10;
        long each = 3_000_000; // only three fit into the 10,000,000 daily limit

        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            CountDownLatch startingGun = new CountDownLatch(1);
            List<Future<Boolean>> results = new ArrayList<>();
            for (int i = 0; i < attempts; i++) {
                results.add(pool.submit(() -> {
                    startingGun.await();
                    try {
                        complianceApi.reserve(customer, Money.ngn(each));
                        return true;
                    } catch (PayBridgeException e) {
                        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.LIMIT_EXCEEDED);
                        return false;
                    }
                }));
            }
            startingGun.countDown();

            int allowed = 0;
            for (Future<Boolean> result : results) {
                if (result.get(60, TimeUnit.SECONDS)) {
                    allowed++;
                }
            }
            assertThat(allowed).isEqualTo(3);
        } finally {
            pool.shutdownNow();
        }
        assertThat(complianceApi.limitStatus(customer).usedTodayMinor()).isEqualTo(9_000_000);
    }

    @Test
    void theLimitsEndpointShowsWhatIsLeft() throws Exception {
        UUID customer = createCustomer();
        complianceApi.reserve(customer, Money.ngn(2_500_000));

        mockMvc.perform(get("/api/compliance/customers/" + customer + "/limits"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kycTier").value("UNVERIFIED"))
                .andExpect(jsonPath("$.perTransactionMinor").value(5_000_000))
                .andExpect(jsonPath("$.dailyMinor").value(10_000_000))
                .andExpect(jsonPath("$.usedTodayMinor").value(2_500_000));
    }

    @Test
    void limitsForAnUnknownCustomerAreNotFound() throws Exception {
        mockMvc.perform(get("/api/compliance/customers/" + UUID.randomUUID() + "/limits"))
                .andExpect(status().isNotFound());
    }
}
