package com.academy.paybridge.remittance;

import com.academy.paybridge.compliance.api.CaseDecision;
import com.academy.paybridge.compliance.api.ComplianceApi;
import com.academy.paybridge.compliance.api.UsdLimitScope;
import com.academy.paybridge.fx.api.Quote;
import com.academy.paybridge.fx.api.QuoteStatus;
import com.academy.paybridge.remittance.api.CreateRemittance;
import com.academy.paybridge.remittance.api.RemittanceApi;
import com.academy.paybridge.remittance.api.RemittanceStatus;
import com.academy.paybridge.remittance.api.RemittanceView;
import com.academy.paybridge.shared.exception.ErrorCode;
import com.academy.paybridge.shared.exception.PayBridgeException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Steps 19 and 20: a remittance can be created exactly once, from a good quote, and compliance genuinely gates it. */
class RemittanceCreationTest extends AbstractRemittanceTest {

    @Autowired ComplianceApi compliance;

    private static void assertCode(Runnable action, ErrorCode expected) {
        assertThatThrownBy(action::run).isInstanceOf(PayBridgeException.class)
                .extracting(e -> ((PayBridgeException) e).getErrorCode()).isEqualTo(expected);
    }

    // ---- creating

    @Test
    void aCleanRemittanceIsCreatedAndWaitsForTheSendersMoney() throws Exception {
        String account = newAccountNumber();

        RemittanceApi.Created created = create(command(newQuote(SELL), "Chidi Okafor", "NG", paybridgeRecipient(account)));

        RemittanceView view = created.remittance();
        assertThat(created.created()).isTrue();
        assertThat(view.status()).isEqualTo(RemittanceStatus.AWAITING_FUNDING);
        assertThat(view.reference()).startsWith("RM-");
        assertThat(view.sellMinor()).isEqualTo(SELL);
        assertThat(view.feeMinor()).isEqualTo(500);
        assertThat(view.buyMinor()).isEqualTo(BUY);
        assertThat(view.recipientAccountMasked()).isEqualTo("******" + account.substring(6)).doesNotContain(account);
    }

    @Test
    void noMoneyMovesWhenARemittanceIsCreated() throws Exception {
        long clearingUsd = systemBalance(com.academy.paybridge.account.api.SystemAccount.REMITTANCE_CLEARING_USD);

        RemittanceView view = createClean(paybridgeRecipient(newAccountNumber()));

        assertThat(entryCount("REMITTANCE_FUNDING", view.reference())).isZero();
        assertThat(systemBalance(com.academy.paybridge.account.api.SystemAccount.REMITTANCE_CLEARING_USD)).isEqualTo(clearingUsd);
    }

    @Test
    void theHistoryRecordsEveryMove() throws Exception {
        RemittanceView view = createClean(paybridgeRecipient(newAccountNumber()));

        assertThat(audit.history(view.id())).containsExactly(
                "- -> CREATED", "CREATED -> SCREENING", "SCREENING -> AWAITING_FUNDING");
    }

    @Test
    void theHistoryCannotBeEditedOrDeleted() throws Exception {
        RemittanceView view = createClean(paybridgeRecipient(newAccountNumber()));

        assertThatThrownBy(() -> jdbc.update("delete from remittance_event where remittance_id = ?", view.id()))
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("update remittance_event set note = 'x' where remittance_id = ?", view.id()))
                .hasMessageContaining("append-only");
    }

    @Test
    void aRemittanceCanBeReadBackByIdAndBySender() throws Exception {
        CreateRemittance command = command(newQuote(SELL), "Chidi Okafor", "NG", paybridgeRecipient(newAccountNumber()));
        RemittanceView view = create(command).remittance();

        assertThat(remittances.get(view.id()).reference()).isEqualTo(view.reference());
        assertThat(remittances.getByReference(view.reference()).id()).isEqualTo(view.id());
        assertThat(remittances.listForSender(PARTNER, command.sender().reference(), 10)).extracting(RemittanceView::id)
                .containsExactly(view.id());
    }

    @Test
    void anUnknownRemittanceIsNotFound() {
        assertThatThrownBy(() -> remittances.get(UUID.randomUUID())).hasMessageContaining("not found");
    }

    // ---- idempotency

    @Test
    void theSameRequestWithTheSameKeyReturnsTheSameRemittance() throws Exception {
        CreateRemittance command = command(newQuote(SELL), "Chidi Okafor", "NG", paybridgeRecipient(newAccountNumber()));
        String client = "client-" + UUID.randomUUID();
        String idem = key();

        RemittanceApi.Created first = remittances.create(command, client, idem);
        RemittanceApi.Created second = remittances.create(command, client, idem);

        assertThat(first.created()).isTrue();
        assertThat(second.created()).isFalse();
        assertThat(second.remittance().id()).isEqualTo(first.remittance().id());
        assertThat(jdbc.queryForObject("select count(*) from remittance where quote_id = ?", Integer.class, command.quoteId())).isEqualTo(1);
    }

    @Test
    void theSameKeyWithADifferentRequestIsRefused() throws Exception {
        String client = "client-" + UUID.randomUUID();
        String idem = key();
        remittances.create(command(newQuote(SELL), "Chidi Okafor", "NG", paybridgeRecipient(newAccountNumber())), client, idem);

        CreateRemittance different = command(newQuote(SELL), "Chidi Okafor", "NG", paybridgeRecipient(newAccountNumber()));

        assertCode(() -> remittances.create(different, client, idem), ErrorCode.IDEMPOTENCY_KEY_REUSED);
    }

    @Test
    void thePartnersOwnReferenceCanOnlyBeUsedOnce() throws Exception {
        CreateRemittance first = command(newQuote(SELL), "Chidi Okafor", "NG", paybridgeRecipient(newAccountNumber()));
        create(first);
        CreateRemittance again = new CreateRemittance(PARTNER, first.partnerReference(), newQuote(SELL).id(), first.sender(), first.recipient());

        assertCode(() -> create(again), ErrorCode.CONFLICT);
    }

    // ---- the quote

    @Test
    void creatingUsesUpTheQuote() throws Exception {
        Quote quote = newQuote(SELL);
        RemittanceView view = create(command(quote, "Chidi Okafor", "NG", paybridgeRecipient(newAccountNumber()))).remittance();

        Quote after = fx.getQuote(quote.id());
        assertThat(after.status()).isEqualTo(QuoteStatus.CONSUMED);
        assertThat(after.consumedBy()).isEqualTo(view.id());
    }

    @Test
    void aQuoteThatDoesNotExistIsRefused() throws Exception {
        Quote real = newQuote(SELL);
        CreateRemittance command = new CreateRemittance(PARTNER, "pref-" + UUID.randomUUID(), UUID.randomUUID(),
                command(real, "Chidi Okafor", "NG", paybridgeRecipient(newAccountNumber())).sender(),
                paybridgeRecipient(newAccountNumber()));

        assertCode(() -> create(command), ErrorCode.RESOURCE_NOT_FOUND);
    }

    @Test
    void anExpiredQuoteIsRefused() throws Exception {
        Quote quote = newQuote(SELL);
        jdbc.update("update fx_quote set expires_at = now() - interval '1 second' where id = ?", quote.id());

        CreateRemittance command = command(quote, "Chidi Okafor", "NG", paybridgeRecipient(newAccountNumber()));

        assertCode(() -> create(command), ErrorCode.QUOTE_EXPIRED);
    }

    @Test
    void aQuoteAlreadyUsedByAnotherRemittanceIsRefused() throws Exception {
        Quote quote = newQuote(SELL);
        create(command(quote, "Chidi Okafor", "NG", paybridgeRecipient(newAccountNumber())));

        CreateRemittance second = command(quote, "Chidi Okafor", "NG", paybridgeRecipient(newAccountNumber()));

        assertCode(() -> create(second), ErrorCode.INVALID_STATE);
    }

    @Test
    void aRefusedCreationLeavesNothingBehindAndTheQuoteStaysUsable() throws Exception {
        Quote quote = newQuote(SELL);
        CreateRemittance bad = command(quote, "Chidi Okafor", "NG", paybridgeRecipient("9999999999"));   // no such account

        assertCode(() -> create(bad), ErrorCode.VALIDATION_FAILED);

        assertThat(fx.getQuote(quote.id()).status()).isEqualTo(QuoteStatus.OPEN);
        assertThat(jdbc.queryForObject("select count(*) from remittance where quote_id = ?", Integer.class, quote.id())).isZero();
        // and the same quote works once the request is right
        assertThat(create(command(quote, "Chidi Okafor", "NG", paybridgeRecipient(newAccountNumber()))).created()).isTrue();
    }

    // ---- validation

    @Test
    void badRequestsAreRefusedBeforeAnythingHappens() throws Exception {
        Quote quote = newQuote(SELL);
        String account = newAccountNumber();
        CreateRemittance ok = command(quote, "Chidi Okafor", "NG", paybridgeRecipient(account));

        CreateRemittance unknownPartner = new CreateRemittance("nobody", ok.partnerReference(), ok.quoteId(), ok.sender(), ok.recipient());
        CreateRemittance noRef = new CreateRemittance(PARTNER, " ", ok.quoteId(), ok.sender(), ok.recipient());
        CreateRemittance badAccount = new CreateRemittance(PARTNER, ok.partnerReference(), ok.quoteId(), ok.sender(),
                paybridgeRecipient("123"));
        CreateRemittance bankWithoutCode = new CreateRemittance(PARTNER, ok.partnerReference(), ok.quoteId(), ok.sender(),
                new CreateRemittance.Recipient(com.academy.paybridge.remittance.api.RecipientType.BANK, "Ada", "NG", null, "0000003333"));
        CreateRemittance badCountry = new CreateRemittance(PARTNER, ok.partnerReference(), ok.quoteId(),
                new CreateRemittance.Sender("s", "Chidi", "NGA", null), ok.recipient());

        for (CreateRemittance bad : List.of(unknownPartner, noRef, badAccount, bankWithoutCode, badCountry)) {
            assertCode(() -> create(bad), ErrorCode.VALIDATION_FAILED);
        }
        assertThat(fx.getQuote(quote.id()).status()).isEqualTo(QuoteStatus.OPEN);
    }

    // ---- the screening gate

    @Test
    void anAmbiguousNameMatchHoldsTheRemittanceForReview() throws Exception {
        RemittanceView view = create(command(newQuote(SELL), "Alina Samplova", "NG", paybridgeRecipient(newAccountNumber()))).remittance();

        assertThat(view.status()).isEqualTo(RemittanceStatus.UNDER_REVIEW);
        assertThat(view.statusReason()).contains("sender");
    }

    @Test
    void aStrongCorroboratedMatchIsBlockedForReviewToo() throws Exception {
        RemittanceView view = create(command(newQuote(SELL), "Zorgon Testlist", "ZZ", paybridgeRecipient(newAccountNumber()))).remittance();

        assertThat(view.status()).isEqualTo(RemittanceStatus.UNDER_REVIEW);
        assertThat(view.statusReason()).contains("BLOCK");
    }

    @Test
    void aListedRecipientHoldsTheRemittanceToo() throws Exception {
        CreateRemittance.Recipient listed = new CreateRemittance.Recipient(com.academy.paybridge.remittance.api.RecipientType.BANK,
                "Kwame Mockerson", "GH", "058", "0000003333");

        RemittanceView view = create(command(newQuote(SELL), "Chidi Okafor", "NG", listed)).remittance();

        assertThat(view.status()).isEqualTo(RemittanceStatus.UNDER_REVIEW);
        assertThat(view.statusReason()).contains("recipient");
    }

    @Test
    void nothingMovesWhileAHitIsUnresolved() throws Exception {
        RemittanceView view = create(command(newQuote(SELL), "Alina Samplova", "NG", paybridgeRecipient(newAccountNumber()))).remittance();

        assertThat(fundingWebhook(view, SELL)).as("funding is held, not applied").isEqualTo("MANUAL_REVIEW");
        runEngine();

        assertThat(statusOf(view)).isEqualTo(RemittanceStatus.UNDER_REVIEW);
        assertThat(entryCount("REMITTANCE_FUNDING", view.reference())).isZero();
    }

    @Test
    void approvingTheReviewResumesTheRemittanceExactlyOnce() throws Exception {
        RemittanceView view = create(command(newQuote(SELL), "Alina Samplova", "NG", paybridgeRecipient(newAccountNumber()))).remittance();

        RemittanceView resumed = reviews.decide(view.id(), CaseDecision.APPROVE, "Birth date differs from the listed person", "reviewer");
        RemittanceView again = reviews.decide(view.id(), CaseDecision.APPROVE, "Birth date differs from the listed person", "reviewer");

        assertThat(resumed.status()).isEqualTo(RemittanceStatus.AWAITING_FUNDING);
        assertThat(again.status()).isEqualTo(RemittanceStatus.AWAITING_FUNDING);
        assertThat(audit.history(view.id()).stream().filter(h -> h.startsWith("UNDER_REVIEW -> AWAITING_FUNDING")).count()).isEqualTo(1);
    }

    @Test
    void rejectingTheReviewFailsTheRemittanceAndGivesTheLimitsBack() throws Exception {
        CreateRemittance command = command(newQuote(SELL), "Alina Samplova", "NG", paybridgeRecipient(newAccountNumber()));
        RemittanceView view = create(command).remittance();
        String senderKey = PARTNER + ":" + command.sender().reference();

        RemittanceView rejected = reviews.decide(view.id(), CaseDecision.REJECT, "Same person as the listed entry", "reviewer");

        assertThat(rejected.status()).isEqualTo(RemittanceStatus.FAILED);
        assertThat(rejected.statusReason()).contains("rejected");
        // the sender's USD 5,000 per-payment and USD 10,000 daily allowance is fully available again
        compliance.reserveUsd(UsdLimitScope.SENDER, senderKey, 500_000);
        compliance.reserveUsd(UsdLimitScope.SENDER, senderKey, 500_000);
    }

    @Test
    void aBlockedRemittanceCannotBeApproved() throws Exception {
        RemittanceView view = create(command(newQuote(SELL), "Zorgon Testlist", "ZZ", paybridgeRecipient(newAccountNumber()))).remittance();

        assertCode(() -> reviews.decide(view.id(), CaseDecision.APPROVE, "Please", "reviewer"), ErrorCode.INVALID_STATE);
        assertThat(statusOf(view)).isEqualTo(RemittanceStatus.UNDER_REVIEW);
    }

    @Test
    void theOppositeDecisionAfterwardsIsRefused() throws Exception {
        RemittanceView view = create(command(newQuote(SELL), "Alina Samplova", "NG", paybridgeRecipient(newAccountNumber()))).remittance();
        reviews.decide(view.id(), CaseDecision.APPROVE, "Cleared", "reviewer");

        assertCode(() -> reviews.decide(view.id(), CaseDecision.REJECT, "Changed my mind", "reviewer"), ErrorCode.INVALID_STATE);
        assertThat(statusOf(view)).isEqualTo(RemittanceStatus.AWAITING_FUNDING);
    }

    @Test
    void aDecisionDoneOnTheComplianceDeskIsPickedUpByTheEngine() throws Exception {
        RemittanceView view = create(command(newQuote(SELL), "Alina Samplova", "NG", paybridgeRecipient(newAccountNumber()))).remittance();
        UUID caseId = jdbc.queryForObject("select sender_case_id from remittance where id = ?", UUID.class, view.id());
        compliance.decideCase(caseId, CaseDecision.APPROVE, "Cleared on the compliance desk", "reviewer");
        assertThat(statusOf(view)).as("not yet noticed").isEqualTo(RemittanceStatus.UNDER_REVIEW);

        runEngine();

        assertThat(statusOf(view)).isEqualTo(RemittanceStatus.AWAITING_FUNDING);
    }

    @Test
    void aPaymentOverTheLimitIsRefusedAndTheQuoteIsNotWasted() throws Exception {
        Quote big = newQuote(600_000);   // over USD 5,000 per payment

        CreateRemittance command = command(big, "Chidi Okafor", "NG", paybridgeRecipient(newAccountNumber()));

        assertCode(() -> create(command), ErrorCode.LIMIT_EXCEEDED);

        assertThat(fx.getQuote(big.id()).status()).isEqualTo(QuoteStatus.OPEN);
    }

    @Test
    void aBurstOfPaymentsFromOneSenderIsSentForReview() throws Exception {
        String senderReference = "burst-" + UUID.randomUUID();
        String account = newAccountNumber();
        RemittanceView last = null;
        for (int i = 0; i < 5; i++) {
            CreateRemittance command = new CreateRemittance(PARTNER, "pref-" + UUID.randomUUID(), newQuote(20_000).id(),
                    new CreateRemittance.Sender(senderReference, "Chidi Okafor", "NG", null), paybridgeRecipient(account));
            last = create(command).remittance();
            if (i < 4) {
                assertThat(last.status()).as("payment %d", i + 1).isEqualTo(RemittanceStatus.AWAITING_FUNDING);
            }
        }

        assertThat(last.status()).isEqualTo(RemittanceStatus.UNDER_REVIEW);
        assertThat(last.statusReason()).contains("VELOCITY");
    }
}
