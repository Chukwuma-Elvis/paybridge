package com.academy.paybridge.remittance;

import com.academy.paybridge.LedgerInvariants;
import com.academy.paybridge.account.api.AccountApi;
import com.academy.paybridge.account.api.SystemAccount;
import com.academy.paybridge.compliance.service.FakeScreeningProvider;
import com.academy.paybridge.fx.api.Corridor;
import com.academy.paybridge.fx.api.FxApi;
import com.academy.paybridge.fx.api.Quote;
import com.academy.paybridge.fx.client.FakeRateProvider;
import com.academy.paybridge.fx.service.FxRateService;
import com.academy.paybridge.remittance.api.CreateRemittance;
import com.academy.paybridge.remittance.api.RecipientType;
import com.academy.paybridge.remittance.api.RemittanceApi;
import com.academy.paybridge.remittance.api.RemittanceStatus;
import com.academy.paybridge.remittance.api.RemittanceView;
import com.academy.paybridge.remittance.client.FakeCorridorPartner;
import com.academy.paybridge.remittance.service.RemittanceAudit;
import com.academy.paybridge.remittance.service.RemittanceProcessor;
import com.academy.paybridge.remittance.service.ReviewService;
import com.academy.paybridge.security.AbstractSecurityTest;
import com.academy.paybridge.transfer.client.Hmac;
import com.academy.paybridge.transfer.service.SystemPayoutDispatcher;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Shared set-up for the remittance tests: a known exchange rate (1500 less 1% = 1485), helpers that build quotes, remittances and
 * signed partner webhooks, and a check after every test that each currency still sums to zero.
 * <p>
 * With sell = USD 1,000.00 (100,000 cents): fee 500 cents, 99,500 converted, naira received = 99,500 x 1485 = 147,757,500 kobo.
 */
public abstract class AbstractRemittanceTest extends AbstractSecurityTest {

    protected static final String PARTNER = "fakepartner";
    protected static final String PARTNER_SECRET = "test-partner-secret-not-a-real-key";
    protected static final long SELL = 100_000;
    protected static final long BUY = 147_757_500L;

    @Autowired protected RemittanceApi remittances;
    @Autowired protected RemittanceProcessor processor;
    @Autowired protected ReviewService reviews;
    @Autowired protected RemittanceAudit audit;
    @Autowired protected FxApi fx;
    @Autowired protected FxRateService rates;
    @Autowired protected FakeRateProvider rateSource;
    @Autowired protected FakeCorridorPartner fakePartner;
    @Autowired protected FakeScreeningProvider watchlist;
    @Autowired protected SystemPayoutDispatcher payoutDispatcher;
    @Autowired protected AccountApi accounts;
    @Autowired protected JdbcTemplate jdbc;

    @BeforeEach
    void aKnownRateAndACleanPartner() {
        quiesce();
        rateSource.reset();
        fakePartner.reset();
        watchlist.reset();
        rates.setManually(Corridor.USD_NGN, new BigDecimal("1500"), "baseline", "test");
        rates.setSpread(Corridor.USD_NGN, 100, "test");
    }

    @AfterEach
    void everyCurrencyStillSumsToZero() {
        LedgerInvariants.everyCurrencySumsToZero(jdbc);
    }

    /**
     * The database is shared by every test in the run, so remittances left half-way by an earlier test would be moved by THIS test's
     * engine passes and spoil its before-and-after balance checks. Settle everything first, so a test only ever sees its own movements.
     */
    private void quiesce() {
        for (int i = 0; i < 3; i++) {
            processor.drain(20);
            payoutDispatcher.dispatchDue();
            payoutDispatcher.reconcile();
        }
    }

    protected static String key() {
        return "key-" + UUID.randomUUID();
    }

    protected Quote newQuote(long sell) {
        return fx.createQuote(Corridor.USD_NGN, sell, "caller-" + UUID.randomUUID(), key()).quote();
    }

    /** A new PayBridge customer account (empty), returning its 10-digit number. */
    protected String newAccountNumber() throws Exception {
        return accounts.getAccount(newAccount()).accountNumber();
    }

    protected CreateRemittance command(Quote quote, String senderName, String senderCountry, CreateRemittance.Recipient recipient) {
        return new CreateRemittance(PARTNER, "pref-" + UUID.randomUUID(), quote.id(),
                new CreateRemittance.Sender("sender-" + UUID.randomUUID(), senderName, senderCountry, LocalDate.of(1990, 5, 5)), recipient);
    }

    protected CreateRemittance.Recipient paybridgeRecipient(String accountNumber) {
        return new CreateRemittance.Recipient(RecipientType.PAYBRIDGE, "Ada Obi", "NG", null, accountNumber);
    }

    /** A recipient at another bank; the fake bank decides what happens by the LAST FOUR digits of the account number. */
    protected CreateRemittance.Recipient bankRecipient(String lastFour) {
        return new CreateRemittance.Recipient(RecipientType.BANK, "Ada Obi", "NG", "058", "000000" + lastFour);
    }

    protected RemittanceApi.Created create(CreateRemittance command) {
        return remittances.create(command, "client-" + UUID.randomUUID(), key());
    }

    /** A clean sender and a PayBridge recipient: goes straight to AWAITING_FUNDING. */
    protected RemittanceView createClean(CreateRemittance.Recipient recipient) {
        return create(command(newQuote(SELL), "Chidi Okafor", "NG", recipient)).remittance();
    }

    protected RemittanceView reload(RemittanceView view) {
        return remittances.get(view.id());
    }

    protected RemittanceStatus statusOf(RemittanceView view) {
        return reload(view).status();
    }

    // ---- partner webhooks

    protected static String fundingBody(String partner, String eventId, String type, String reference, long amountMinor, String currency) {
        return ("{\"partner\":\"%s\",\"eventId\":\"%s\",\"type\":\"%s\",\"reference\":\"%s\",\"partnerReference\":\"P-%s\","
                + "\"amountMinor\":%d,\"currency\":\"%s\"}").formatted(partner, eventId, type, reference, eventId, amountMinor, currency);
    }

    /** Sends a signed partner webhook and returns the pipeline's answer (APPLIED, DUPLICATE, MANUAL_REVIEW, ...). */
    protected String partnerWebhook(String secret, String body) throws Exception {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        String json = mockMvc.perform(post("/api/webhooks/partner").header("x-partner-signature", Hmac.hmacSha512Hex(secret, bytes))
                        .contentType(MediaType.APPLICATION_JSON).content(bytes))
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(json, "$.result");
    }

    protected String fundingWebhook(RemittanceView remittance, long amountMinor) throws Exception {
        return partnerWebhook(PARTNER_SECRET, fundingBody(PARTNER, "evt-" + UUID.randomUUID(), "funding.confirmed",
                remittance.reference(), amountMinor, "USD"));
    }

    // ---- money

    protected long systemBalance(SystemAccount account) {
        return accounts.getAccount(accounts.systemAccountId(account)).balanceMinor();
    }

    protected long partnerBalance() {
        return accounts.getAccount(accounts.ensureSystemAccount("PARTNER_SETTLEMENT_USD:" + PARTNER, "USD")).balanceMinor();
    }

    protected long balanceOfNumber(String accountNumber) {
        return accounts.getByAccountNumber(accountNumber).balanceMinor();
    }

    protected long entryCount(String type, String reference) {
        Long count = jdbc.queryForObject("select count(*) from journal_entry where entry_type = ? and reference = ?", Long.class,
                type, reference);
        return count == null ? 0 : count;
    }

    /** Runs the engine until it has nothing left to do. */
    protected void runEngine() {
        processor.drain(20);
    }
}
