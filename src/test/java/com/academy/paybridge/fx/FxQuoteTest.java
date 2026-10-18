package com.academy.paybridge.fx;

import com.academy.paybridge.fx.api.Corridor;
import com.academy.paybridge.fx.api.FxApi;
import com.academy.paybridge.fx.api.Quote;
import com.academy.paybridge.fx.api.QuoteStatus;
import com.academy.paybridge.fx.client.FakeRateProvider;
import com.academy.paybridge.fx.service.FxRateService;
import com.academy.paybridge.security.AbstractSecurityTest;
import com.academy.paybridge.security.domain.ApiKeyRole;
import com.academy.paybridge.shared.exception.ErrorCode;
import com.academy.paybridge.shared.exception.PayBridgeException;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Quotes: a locked rate that is used at most once, and only while it is fresh. */
class FxQuoteTest extends AbstractSecurityTest {

    private static final String KEY = "X-API-Key";

    @Autowired FxApi fxApi;
    @Autowired FxRateService rates;
    @Autowired FakeRateProvider source;
    @Autowired JdbcTemplate jdbc;

    private MockMvc mvc;
    private String client;

    @BeforeEach
    void aKnownRate() {
        mvc = secured();
        client = newKey(ApiKeyRole.CLIENT);
        source.reset();
        rates.setManually(Corridor.USD_NGN, new BigDecimal("1500"), "baseline", "test");
        rates.setSpread(Corridor.USD_NGN, 100, "test");
    }

    private static String key() {
        return "key-" + UUID.randomUUID();
    }

    private Quote newQuote(long sell) {
        return fxApi.createQuote(Corridor.USD_NGN, sell, "caller-" + UUID.randomUUID(), key()).quote();
    }

    // ---- the numbers

    @Test
    void aQuoteFreezesTheRateTheFeeAndTheResult() throws Exception {
        mvc.perform(post("/api/fx/quotes").header(KEY, client).header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"corridor\":\"USD_NGN\",\"sellMinor\":100000}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.sellMinor").value(100000))
                .andExpect(jsonPath("$.feeMinor").value(500))                // 0.5% of 1,000.00
                .andExpect(jsonPath("$.customerRate").value(1485.0))
                .andExpect(jsonPath("$.buyMinor").value(147_757_500L))       // 99,500 x 1485
                .andExpect(jsonPath("$.status").value("OPEN"));
    }

    @Test
    void laterRateChangesDoNotTouchAnIssuedQuote() {
        Quote quote = newQuote(100_000);

        rates.setManually(Corridor.USD_NGN, new BigDecimal("1600"), "moved", "test");

        Quote again = fxApi.getQuote(quote.id());
        assertThat(again.customerRate()).isEqualByComparingTo("1485");
        assertThat(again.buyMinor()).isEqualTo(quote.buyMinor());
    }

    // ---- idempotency

    @Test
    void theSameKeyAndRequestReturnsTheSameQuote() throws Exception {
        String idem = key();
        String body = "{\"corridor\":\"USD_NGN\",\"sellMinor\":50000}";
        String first = mvc.perform(post("/api/fx/quotes").header(KEY, client).header("Idempotency-Key", idem)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();

        mvc.perform(post("/api/fx/quotes").header(KEY, client).header("Idempotency-Key", idem)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value((String) JsonPath.read(first, "$.id")));
    }

    @Test
    void theSameKeyWithADifferentAmountIsRefused() throws Exception {
        String idem = key();
        mvc.perform(post("/api/fx/quotes").header(KEY, client).header("Idempotency-Key", idem)
                .contentType(MediaType.APPLICATION_JSON).content("{\"corridor\":\"USD_NGN\",\"sellMinor\":50000}"));

        mvc.perform(post("/api/fx/quotes").header(KEY, client).header("Idempotency-Key", idem)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"corridor\":\"USD_NGN\",\"sellMinor\":60000}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
    }

    @Test
    void theIdempotencyKeyIsMandatoryAndChecked() throws Exception {
        mvc.perform(post("/api/fx/quotes").header(KEY, client)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"corridor\":\"USD_NGN\",\"sellMinor\":50000}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/fx/quotes").header(KEY, client).header("Idempotency-Key", "short")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"corridor\":\"USD_NGN\",\"sellMinor\":50000}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void simultaneousRequestsWithOneKeyProduceExactlyOneQuote() throws Exception {
        String caller = "caller-" + UUID.randomUUID();
        String idem = key();
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch gun = new CountDownLatch(1);
        List<Future<FxApi.QuoteIssued>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            results.add(pool.submit(() -> {
                gun.await();
                return fxApi.createQuote(Corridor.USD_NGN, 70_000, caller, idem);
            }));
        }
        gun.countDown();
        List<UUID> ids = new ArrayList<>();
        int created = 0;
        for (Future<FxApi.QuoteIssued> f : results) {
            FxApi.QuoteIssued issued = f.get();
            ids.add(issued.quote().id());
            if (issued.created()) {
                created++;
            }
        }
        pool.shutdown();

        assertThat(ids.stream().distinct()).hasSize(1);
        assertThat(created).isEqualTo(1);
    }

    // ---- limits

    @Test
    void amountsOutsideTheLimitsAreRefused() throws Exception {
        for (String sell : new String[]{"0", "-5", "499", "1000001"}) {
            mvc.perform(post("/api/fx/quotes").header(KEY, client).header("Idempotency-Key", key())
                            .contentType(MediaType.APPLICATION_JSON).content("{\"corridor\":\"USD_NGN\",\"sellMinor\":" + sell + "}"))
                    .andExpect(status().isBadRequest());
        }
    }

    @Test
    void quotesCanBeReadBackAndUnknownOnesAreNotFound() throws Exception {
        Quote quote = newQuote(100_000);
        mvc.perform(get("/api/fx/quotes/" + quote.id()).header(KEY, client))
                .andExpect(status().isOk()).andExpect(jsonPath("$.reference").value(quote.reference()));
        mvc.perform(get("/api/fx/quotes/" + UUID.randomUUID()).header(KEY, client)).andExpect(status().isNotFound());
    }

    // ---- using a quote

    @Test
    void aQuoteCanBeUsedOnceAndARetryByTheSameUserIsHarmless() {
        Quote quote = newQuote(100_000);
        UUID remittance = UUID.randomUUID();

        Quote used = fxApi.consumeQuote(quote.id(), remittance);
        Quote retried = fxApi.consumeQuote(quote.id(), remittance);

        assertThat(used.status()).isEqualTo(QuoteStatus.CONSUMED);
        assertThat(used.consumedBy()).isEqualTo(remittance);
        assertThat(retried.id()).isEqualTo(used.id());
    }

    @Test
    void aSecondRemittanceCannotUseTheSameQuote() {
        Quote quote = newQuote(100_000);
        fxApi.consumeQuote(quote.id(), UUID.randomUUID());

        assertThatThrownBy(() -> fxApi.consumeQuote(quote.id(), UUID.randomUUID()))
                .isInstanceOf(PayBridgeException.class)
                .extracting(e -> ((PayBridgeException) e).getErrorCode()).isEqualTo(ErrorCode.INVALID_STATE);
    }

    @Test
    void anExpiredQuoteCannotBeUsedAndIsReportedAsExpired() {
        Quote quote = newQuote(100_000);
        jdbc.update("update fx_quote set expires_at = now() - interval '1 second' where id = ?", quote.id());

        assertThat(fxApi.getQuote(quote.id()).status()).isEqualTo(QuoteStatus.EXPIRED);
        assertThatThrownBy(() -> fxApi.consumeQuote(quote.id(), UUID.randomUUID()))
                .isInstanceOf(PayBridgeException.class)
                .extracting(e -> ((PayBridgeException) e).getErrorCode()).isEqualTo(ErrorCode.QUOTE_EXPIRED);
    }

    @Test
    void anUnknownQuoteCannotBeUsed() {
        assertThatThrownBy(() -> fxApi.consumeQuote(UUID.randomUUID(), UUID.randomUUID()))
                .isInstanceOf(PayBridgeException.class)
                .extracting(e -> ((PayBridgeException) e).getErrorCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);
    }

    @Test
    void whenManyTryToUseOneQuoteAtOnceExactlyOneWins() throws Exception {
        Quote quote = newQuote(100_000);
        int threads = 10;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch gun = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            UUID consumer = UUID.randomUUID();
            results.add(pool.submit(() -> {
                gun.await();
                try {
                    fxApi.consumeQuote(quote.id(), consumer);
                    return true;
                } catch (PayBridgeException e) {
                    return false;
                }
            }));
        }
        gun.countDown();
        int winners = 0;
        for (Future<Boolean> f : results) {
            if (f.get()) {
                winners++;
            }
        }
        pool.shutdown();

        assertThat(winners).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from fx_quote where id = ? and status = 'CONSUMED'", Integer.class,
                quote.id())).isEqualTo(1);
    }
}
