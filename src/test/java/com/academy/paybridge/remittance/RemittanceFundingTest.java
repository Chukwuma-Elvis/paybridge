package com.academy.paybridge.remittance;

import com.academy.paybridge.account.api.SystemAccount;
import com.academy.paybridge.compliance.api.CaseDecision;
import com.academy.paybridge.remittance.api.RemittanceStatus;
import com.academy.paybridge.remittance.api.RemittanceView;
import com.academy.paybridge.transfer.client.Hmac;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Step 21: the corridor partner's funding confirmation is trusted exactly as much as a payment provider's webhook. */
class RemittanceFundingTest extends AbstractRemittanceTest {

    private static final String OTHER_SECRET = "test-other-partner-secret-not-real";

    private RemittanceView awaiting() throws Exception {
        RemittanceView view = createClean(paybridgeRecipient(newAccountNumber()));
        assertThat(view.status()).isEqualTo(RemittanceStatus.AWAITING_FUNDING);
        return view;
    }

    // ---- the happy path and repeats

    @Test
    void aValidFundingIsAppliedOnceAndPostsTheDollars() throws Exception {
        RemittanceView view = awaiting();
        long clearing = systemBalance(SystemAccount.REMITTANCE_CLEARING_USD);
        long partner = partnerBalance();

        String result = fundingWebhook(view, SELL);

        assertThat(result).isEqualTo("APPLIED");
        assertThat(statusOf(view)).isEqualTo(RemittanceStatus.FUNDED);
        assertThat(entryCount("REMITTANCE_FUNDING", view.reference())).isEqualTo(1);
        assertThat(systemBalance(SystemAccount.REMITTANCE_CLEARING_USD)).isEqualTo(clearing + SELL);
        assertThat(partnerBalance()).as("what the partner holds for us").isEqualTo(partner - SELL);
    }

    @Test
    void theSameDeliveryTwiceIsADuplicateAndChangesNothing() throws Exception {
        RemittanceView view = awaiting();
        String body = fundingBody(PARTNER, "evt-" + UUID.randomUUID(), "funding.confirmed", view.reference(), SELL, "USD");

        assertThat(partnerWebhook(PARTNER_SECRET, body)).isEqualTo("APPLIED");
        assertThat(partnerWebhook(PARTNER_SECRET, body)).isEqualTo("DUPLICATE");

        assertThat(entryCount("REMITTANCE_FUNDING", view.reference())).isEqualTo(1);
    }

    @Test
    void aFreshEventForAnAlreadyFundedRemittanceIsHarmless() throws Exception {
        RemittanceView view = awaiting();
        fundingWebhook(view, SELL);

        assertThat(fundingWebhook(view, SELL)).isEqualTo("APPLIED");

        assertThat(entryCount("REMITTANCE_FUNDING", view.reference())).isEqualTo(1);
        assertThat(statusOf(view)).isEqualTo(RemittanceStatus.FUNDED);
    }

    @Test
    void theSameFundingArrivingManyTimesAtOnceIsAppliedOnce() throws Exception {
        RemittanceView view = awaiting();
        int threads = 6;
        var pool = java.util.concurrent.Executors.newFixedThreadPool(threads);
        var gun = new java.util.concurrent.CountDownLatch(1);
        var results = new java.util.ArrayList<java.util.concurrent.Future<String>>();
        for (int i = 0; i < threads; i++) {
            String body = fundingBody(PARTNER, "evt-" + UUID.randomUUID(), "funding.confirmed", view.reference(), SELL, "USD");
            results.add(pool.submit(() -> {
                gun.await();
                return partnerWebhook(PARTNER_SECRET, body);
            }));
        }
        gun.countDown();
        for (var f : results) {
            f.get();
        }
        pool.shutdown();

        assertThat(entryCount("REMITTANCE_FUNDING", view.reference())).isEqualTo(1);
        assertThat(statusOf(view)).isEqualTo(RemittanceStatus.FUNDED);
    }

    // ---- trust

    @Test
    void aForgedSignatureIsRefusedAndNothingChanges() throws Exception {
        RemittanceView view = awaiting();
        byte[] body = fundingBody(PARTNER, "evt-1", "funding.confirmed", view.reference(), SELL, "USD").getBytes(StandardCharsets.UTF_8);

        mockMvc.perform(post("/api/webhooks/partner").header("x-partner-signature", Hmac.hmacSha512Hex("a-guess", body))
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/webhooks/partner").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());

        assertThat(statusOf(view)).isEqualTo(RemittanceStatus.AWAITING_FUNDING);
    }

    @Test
    void onePartnerCannotSpeakForAnotherEvenWithAValidSecretOfItsOwn() throws Exception {
        RemittanceView view = awaiting();
        // signed with otherpartner's secret but claiming to be fakepartner: the secret is picked by the claimed name, so it cannot match
        byte[] body = fundingBody(PARTNER, "evt-2", "funding.confirmed", view.reference(), SELL, "USD").getBytes(StandardCharsets.UTF_8);

        mockMvc.perform(post("/api/webhooks/partner").header("x-partner-signature", Hmac.hmacSha512Hex(OTHER_SECRET, body))
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isUnauthorized());

        assertThat(statusOf(view)).isEqualTo(RemittanceStatus.AWAITING_FUNDING);
    }

    @Test
    void aPartnerCannotFundAnotherPartnersRemittance() throws Exception {
        RemittanceView view = awaiting();

        String result = partnerWebhook(OTHER_SECRET, fundingBody("otherpartner", "evt-" + UUID.randomUUID(), "funding.confirmed",
                view.reference(), SELL, "USD"));

        assertThat(result).isEqualTo("UNMATCHED");
        assertThat(statusOf(view)).isEqualTo(RemittanceStatus.AWAITING_FUNDING);
    }

    @Test
    void aFundingForSomethingWeNeverHeardOfIsUnmatched() throws Exception {
        assertThat(partnerWebhook(PARTNER_SECRET, fundingBody(PARTNER, "evt-" + UUID.randomUUID(), "funding.confirmed",
                "RM-DOESNOTEXIST0000", SELL, "USD"))).isEqualTo("UNMATCHED");
    }

    @Test
    void aSignedButMalformedBodyIsABadRequest() throws Exception {
        byte[] body = "{\"partner\":\"fakepartner\"}".getBytes(StandardCharsets.UTF_8);

        mockMvc.perform(post("/api/webhooks/partner").header("x-partner-signature", Hmac.hmacSha512Hex(PARTNER_SECRET, body))
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());
    }

    @Test
    void anEventWeDoNotActOnIsRecordedAndIgnored() throws Exception {
        RemittanceView view = awaiting();

        String result = partnerWebhook(PARTNER_SECRET, fundingBody(PARTNER, "evt-" + UUID.randomUUID(), "partner.maintenance",
                view.reference(), SELL, "USD"));

        assertThat(result).isEqualTo("IGNORED");
        assertThat(statusOf(view)).isEqualTo(RemittanceStatus.AWAITING_FUNDING);
    }

    // ---- cross-checking

    @Test
    void aWrongAmountIsNotAppliedAndSendsTheRemittanceForReview() throws Exception {
        RemittanceView view = awaiting();

        String result = fundingWebhook(view, SELL - 1);

        assertThat(result).isEqualTo("AMOUNT_MISMATCH");
        assertThat(statusOf(view)).isEqualTo(RemittanceStatus.UNDER_REVIEW);
        assertThat(entryCount("REMITTANCE_FUNDING", view.reference())).isZero();
    }

    @Test
    void aWrongCurrencyIsNotAppliedEither() throws Exception {
        RemittanceView view = awaiting();

        String result = partnerWebhook(PARTNER_SECRET, fundingBody(PARTNER, "evt-" + UUID.randomUUID(), "funding.confirmed",
                view.reference(), SELL, "EUR"));

        assertThat(result).isEqualTo("AMOUNT_MISMATCH");
        assertThat(entryCount("REMITTANCE_FUNDING", view.reference())).isZero();
    }

    @Test
    void afterAMismatchIsResolvedTheCorrectFundingCanBeApplied() throws Exception {
        RemittanceView view = awaiting();
        fundingWebhook(view, SELL - 1);
        reviews.decide(view.id(), CaseDecision.APPROVE, "Raised with the partner, who will resend the correct amount", "reviewer");
        assertThat(statusOf(view)).isEqualTo(RemittanceStatus.AWAITING_FUNDING);

        assertThat(fundingWebhook(view, SELL)).isEqualTo("APPLIED");

        assertThat(statusOf(view)).isEqualTo(RemittanceStatus.FUNDED);
        assertThat(entryCount("REMITTANCE_FUNDING", view.reference())).isEqualTo(1);
    }

    // ---- the partner says it failed, or the remittance is already dead

    @Test
    void aFailedFundingFailsTheRemittanceAndGivesTheLimitsBack() throws Exception {
        RemittanceView view = awaiting();

        String result = partnerWebhook(PARTNER_SECRET, fundingBody(PARTNER, "evt-" + UUID.randomUUID(), "funding.failed",
                view.reference(), SELL, "USD"));

        assertThat(result).isEqualTo("APPLIED");
        assertThat(statusOf(view)).isEqualTo(RemittanceStatus.FAILED);
        assertThat(jdbc.queryForObject("select limits_reserved from remittance where id = ?", Boolean.class, view.id())).isFalse();
    }

    @Test
    void moneyArrivingForAFailedRemittanceGoesToManualReview() throws Exception {
        RemittanceView view = awaiting();
        partnerWebhook(PARTNER_SECRET, fundingBody(PARTNER, "evt-" + UUID.randomUUID(), "funding.failed", view.reference(), SELL, "USD"));

        String result = fundingWebhook(view, SELL);

        assertThat(result).isEqualTo("MANUAL_REVIEW");
        assertThat(entryCount("REMITTANCE_FUNDING", view.reference())).isZero();
        assertThat(statusOf(view)).isEqualTo(RemittanceStatus.FAILED);
    }

    // ---- the safety net: asking the partner when no webhook came

    @Test
    void aFundingTheWebhookNeverReportedIsFoundByAskingThePartner() throws Exception {
        RemittanceView view = awaiting();
        fakePartner.fund(view.reference(), SELL);

        processor.checkFunding();

        assertThat(statusOf(view)).isEqualTo(RemittanceStatus.FUNDED);
        assertThat(entryCount("REMITTANCE_FUNDING", view.reference())).isEqualTo(1);
    }

    @Test
    void askingThePartnerAfterTheWebhookDidArriveDoesNotFundTwice() throws Exception {
        RemittanceView view = awaiting();
        fundingWebhook(view, SELL);
        fakePartner.fund(view.reference(), SELL);

        processor.checkFunding();

        assertThat(entryCount("REMITTANCE_FUNDING", view.reference())).isEqualTo(1);
    }

    @Test
    void thePartnerReportingAWrongAmountSendsTheRemittanceForReview() throws Exception {
        RemittanceView view = awaiting();
        fakePartner.fund(view.reference(), SELL + 100);

        processor.checkFunding();

        assertThat(statusOf(view)).isEqualTo(RemittanceStatus.UNDER_REVIEW);
        assertThat(entryCount("REMITTANCE_FUNDING", view.reference())).isZero();
    }

    @Test
    void anUnreachablePartnerChangesNothing() throws Exception {
        RemittanceView view = awaiting();
        fakePartner.fund(view.reference(), SELL);
        fakePartner.setUnavailable(true);

        processor.checkFunding();

        assertThat(statusOf(view)).as("unknown is never treated as not funded").isEqualTo(RemittanceStatus.AWAITING_FUNDING);
    }

    @Test
    void thePartnerReportingAFailureFailsTheRemittance() throws Exception {
        RemittanceView view = awaiting();
        fakePartner.fail(view.reference());

        processor.checkFunding();

        assertThat(statusOf(view)).isEqualTo(RemittanceStatus.FAILED);
    }

    @Test
    void aRemittanceTheFundingWindowRanOutOnIsFailed() throws Exception {
        RemittanceView view = awaiting();
        jdbc.update("update remittance set funding_expires_at = now() - interval '1 minute' where id = ?", view.id());

        processor.checkFunding();

        assertThat(statusOf(view)).isEqualTo(RemittanceStatus.FAILED);
        assertThat(view.reference()).isNotBlank();
    }

    @Test
    void aRemittanceStillInsideTheFundingWindowIsLeftWaiting() throws Exception {
        RemittanceView view = awaiting();

        processor.checkFunding();

        assertThat(statusOf(view)).isEqualTo(RemittanceStatus.AWAITING_FUNDING);
    }
}
