package com.academy.paybridge.transfer.service;

import com.academy.paybridge.AbstractIntegrationTest;
import com.academy.paybridge.transfer.client.GatewayOutcome;
import com.academy.paybridge.transfer.client.ProviderEvent;
import com.academy.paybridge.transfer.domain.WebhookStatus;
import com.academy.paybridge.transfer.service.WebhookRecorder.Claim;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** The rules for "may I work on this webhook?": who owns it, when a repeat is ignored, and when a failed one is retried. */
class WebhookRecorderTest extends AbstractIntegrationTest {

    @Autowired
    private WebhookRecorder recorder;

    @Autowired
    private JdbcTemplate jdbc;

    private static ProviderEvent event(String eventId) {
        return new ProviderEvent(eventId, "transfer.success", GatewayOutcome.SUCCESS, "pb-" + UUID.randomUUID(), "TRF_1", 1_000L, "NGN", null);
    }

    private static String newId() {
        return "evt-" + UUID.randomUUID();
    }

    private String statusOf(UUID rowId) {
        return jdbc.queryForObject("select status from webhook_event where id = ?", String.class, rowId);
    }

    private int attemptsOf(UUID rowId) {
        return jdbc.queryForObject("select attempts from webhook_event where id = ?", Integer.class, rowId);
    }

    @Test
    void aNewEventIsOwnedByWhoeverClaimsItFirst() {
        Claim claim = recorder.claim("paystack", event(newId()), "fingerprint");

        assertThat(claim.kind()).isEqualTo(Claim.Kind.OWNED);
        assertThat(statusOf(claim.eventRowId())).isEqualTo("RECEIVED");
        assertThat(attemptsOf(claim.eventRowId())).isEqualTo(1);
    }

    @Test
    void theSameEventClaimedAgainWhileItIsBeingWorkedOnIsInProgress() {
        String id = newId();
        recorder.claim("paystack", event(id), "fingerprint");

        assertThat(recorder.claim("paystack", event(id), "fingerprint").kind()).isEqualTo(Claim.Kind.IN_PROGRESS);
    }

    @Test
    void aFinishedEventIsADuplicateForEveryFinalState() {
        for (WebhookStatus finalState : new WebhookStatus[]{WebhookStatus.APPLIED, WebhookStatus.IGNORED, WebhookStatus.UNMATCHED,
                WebhookStatus.AMOUNT_MISMATCH, WebhookStatus.MANUAL_REVIEW}) {
            String id = newId();
            Claim first = recorder.claim("paystack", event(id), "fingerprint");
            recorder.finish(first.eventRowId(), finalState, "done");

            assertThat(recorder.claim("paystack", event(id), "fingerprint").kind())
                    .as("a repeat of an event that ended %s", finalState)
                    .isEqualTo(Claim.Kind.DUPLICATE);
        }
    }

    @Test
    void anEventThatFailedIsTakenOverForAnotherTry() {
        String id = newId();
        Claim first = recorder.claim("paystack", event(id), "fingerprint");
        recorder.finish(first.eventRowId(), WebhookStatus.FAILED, "database was down");

        Claim retry = recorder.claim("paystack", event(id), "fingerprint");

        assertThat(retry.kind()).isEqualTo(Claim.Kind.OWNED);
        assertThat(retry.eventRowId()).isEqualTo(first.eventRowId());
        assertThat(statusOf(retry.eventRowId())).isEqualTo("RECEIVED");
        assertThat(attemptsOf(retry.eventRowId())).isEqualTo(2);
    }

    @Test
    void anEventClaimedLongAgoAndNeverFinishedIsTakenOverBecauseWhoeverHadItCrashed() {
        String id = newId();
        Claim first = recorder.claim("paystack", event(id), "fingerprint");
        jdbc.update("update webhook_event set updated_at = now() - interval '10 minutes' where id = ?", first.eventRowId());

        Claim retry = recorder.claim("paystack", event(id), "fingerprint");

        assertThat(retry.kind()).isEqualTo(Claim.Kind.OWNED);
        assertThat(attemptsOf(first.eventRowId())).isEqualTo(2);
    }

    @Test
    void theSameEventIdFromAnotherProviderIsADifferentEvent() {
        String id = newId();
        recorder.claim("paystack", event(id), "fingerprint");

        assertThat(recorder.claim("monnify", event(id), "fingerprint").kind()).isEqualTo(Claim.Kind.OWNED);
    }

    @Test
    void theRecordKeepsTheFingerprintAndNotTheBody() {
        Claim claim = recorder.claim("paystack", event(newId()), "abc123fingerprint");

        assertThat(jdbc.queryForObject("select payload_sha256 from webhook_event where id = ?", String.class, claim.eventRowId()))
                .isEqualTo("abc123fingerprint");
        assertThat(jdbc.queryForList("select column_name from information_schema.columns where table_name = 'webhook_event'", String.class))
                .doesNotContain("payload", "body", "raw_body");
    }

    @Test
    void aEventWithNoOutcomeOrAmountCanStillBeRecorded() {
        ProviderEvent bare = new ProviderEvent(newId(), "charge.success", null, null, null, null, null, null);

        Claim claim = recorder.claim("paystack", bare, "fingerprint");

        assertThat(claim.kind()).isEqualTo(Claim.Kind.OWNED);
    }

    @Test
    void aLongDetailIsShortenedToFit() {
        Claim claim = recorder.claim("paystack", event(newId()), "fingerprint");

        recorder.finish(claim.eventRowId(), WebhookStatus.FAILED, "x".repeat(2_000));

        assertThat(jdbc.queryForObject("select length(detail) from webhook_event where id = ?", Integer.class, claim.eventRowId()))
                .isEqualTo(500);
    }
}
