package com.academy.paybridge.remittance;

import com.academy.paybridge.fx.api.Corridor;
import com.academy.paybridge.remittance.api.RecipientType;
import com.academy.paybridge.remittance.api.RemittanceStatus;
import com.academy.paybridge.remittance.domain.Remittance;
import com.academy.paybridge.shared.exception.ErrorCode;
import com.academy.paybridge.shared.exception.PayBridgeException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static com.academy.paybridge.remittance.api.RemittanceStatus.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The state machine, checked move by move: every legal move works, every other move is refused, and final states are final. */
class RemittanceStatusTest {

    /** The one source of truth for this test: exactly these moves are legal. */
    private static final Map<RemittanceStatus, Set<RemittanceStatus>> LEGAL = new EnumMap<>(RemittanceStatus.class);

    static {
        LEGAL.put(CREATED, EnumSet.of(SCREENING, FAILED));
        LEGAL.put(SCREENING, EnumSet.of(UNDER_REVIEW, AWAITING_FUNDING, FAILED));
        LEGAL.put(UNDER_REVIEW, EnumSet.of(AWAITING_FUNDING, FAILED, CANCELLED));
        LEGAL.put(AWAITING_FUNDING, EnumSet.of(FUNDED, UNDER_REVIEW, FAILED, CANCELLED));
        LEGAL.put(FUNDED, EnumSet.of(CONVERTED, REFUND_PENDING));
        LEGAL.put(CONVERTED, EnumSet.of(PAYOUT_PENDING, PAID, REFUND_PENDING));
        LEGAL.put(PAYOUT_PENDING, EnumSet.of(PAID, REFUND_PENDING));
        LEGAL.put(REFUND_PENDING, EnumSet.of(REFUNDED));
        LEGAL.put(PAID, EnumSet.noneOf(RemittanceStatus.class));
        LEGAL.put(FAILED, EnumSet.noneOf(RemittanceStatus.class));
        LEGAL.put(REFUNDED, EnumSet.noneOf(RemittanceStatus.class));
        LEGAL.put(CANCELLED, EnumSet.noneOf(RemittanceStatus.class));
    }

    @Test
    void everyLegalMoveIsAllowedAndEveryOtherMoveIsRefused() {
        for (RemittanceStatus from : RemittanceStatus.values()) {
            for (RemittanceStatus to : RemittanceStatus.values()) {
                assertThat(from.canMoveTo(to)).as("%s -> %s", from, to).isEqualTo(LEGAL.get(from).contains(to));
            }
        }
    }

    @Test
    void finalStatesAreFinalAndHaveNoWayOut() {
        for (RemittanceStatus status : RemittanceStatus.values()) {
            boolean expectedFinal = LEGAL.get(status).isEmpty();
            assertThat(status.isFinal()).as(status.name()).isEqualTo(expectedFinal);
        }
    }

    @Test
    void moneyOnlyCountsAsTakenInFromFundedOnwards() {
        assertThat(EnumSet.allOf(RemittanceStatus.class).stream().filter(RemittanceStatus::isFunded))
                .containsExactlyInAnyOrder(FUNDED, CONVERTED, PAYOUT_PENDING, REFUND_PENDING);
    }

    @Test
    void noPathLeadsToPaidWithoutBeingFundedFirst() {
        // PAID can only be reached from CONVERTED or PAYOUT_PENDING, both of which are funded states
        for (RemittanceStatus from : RemittanceStatus.values()) {
            if (from.canMoveTo(PAID)) {
                assertThat(from.isFunded()).as("%s can move to PAID", from).isTrue();
            }
        }
    }

    // ---- the entity enforces it

    private static Remittance remittance() {
        return Remittance.create(new Remittance.Facts(UUID.randomUUID(), "RM-TEST", "c", "k", "h", "fakepartner", "p", Corridor.USD_NGN,
                UUID.randomUUID(), 100_000, 500, 147_757_500L, "s", "Sender", "NG", null, "r", "Recipient", "NG", RecipientType.BANK,
                "058", "0000003333", Instant.now()));
    }

    @Test
    void aNewRemittanceStartsCreated() {
        assertThat(remittance().getStatus()).isEqualTo(CREATED);
    }

    @Test
    void theEntityRefusesAnIllegalMoveAndKeepsItsStatus() {
        Remittance r = remittance();

        assertThatThrownBy(() -> r.moveTo(PAID, null)).isInstanceOf(PayBridgeException.class)
                .extracting(e -> ((PayBridgeException) e).getErrorCode()).isEqualTo(ErrorCode.INVALID_STATE);
        assertThat(r.getStatus()).isEqualTo(CREATED);
    }

    @Test
    void theEntityFollowsTheHappyPathAndThenIsStuckAtPaid() {
        Remittance r = remittance();
        for (RemittanceStatus next : new RemittanceStatus[]{SCREENING, AWAITING_FUNDING, FUNDED, CONVERTED, PAYOUT_PENDING, PAID}) {
            r.moveTo(next, null);
        }

        assertThat(r.getStatus()).isEqualTo(PAID);
        for (RemittanceStatus any : RemittanceStatus.values()) {
            assertThatThrownBy(() -> r.moveTo(any, null)).isInstanceOf(PayBridgeException.class);
        }
    }

    @Test
    void aLongReasonIsShortenedNotRejected() {
        Remittance r = remittance();
        r.moveTo(SCREENING, "x".repeat(1_000));
        assertThat(r.getStatusReason()).hasSize(255);
    }
}
