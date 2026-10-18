package com.academy.paybridge.fx.service;

import com.academy.paybridge.fx.api.Corridor;
import com.academy.paybridge.fx.api.CurrentRate;
import com.academy.paybridge.fx.client.ProviderRate;
import com.academy.paybridge.fx.client.RateProvider;
import com.academy.paybridge.fx.client.RateProviderUnavailableException;
import com.academy.paybridge.fx.domain.FxRate;
import com.academy.paybridge.fx.domain.RateStatus;
import com.academy.paybridge.shared.exception.ErrorCode;
import com.academy.paybridge.shared.exception.PayBridgeException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The service's decisions, with the rate source and the store replaced by stand-ins, so nothing needs a database or a clock. */
class FxRateServiceUnitTest {

    private static final Instant NOW = Instant.parse("2026-01-01T12:00:00Z");
    private static final FxProperties PROPERTIES = new FxProperties(false, Duration.ofMinutes(5), new BigDecimal("5"), 100,
            Duration.ofMinutes(5), new BigDecimal("100"), new BigDecimal("5000"), new FxProperties.Refresher(false, Duration.ofSeconds(60)),
            new FxProperties.Quotes(50, 100, 500, 1_000_000));

    private RateProvider provider;
    private FxRateStore store;
    private SimpleMeterRegistry meters;
    private FxRateService service;

    @BeforeEach
    void setUp() {
        provider = Mockito.mock(RateProvider.class);
        store = Mockito.mock(FxRateStore.class);
        meters = new SimpleMeterRegistry();
        service = new FxRateService(provider, store, PROPERTIES, meters, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static FxRate stored(String mid, Instant retrievedAt) {
        return new FxRate(Corridor.USD_NGN, new BigDecimal(mid), "test", retrievedAt, RateStatus.ACTIVE, null, null);
    }

    @Test
    void withNoRateAtAllTheRateIsUnavailable() {
        when(store.latestActive(Corridor.USD_NGN)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.currentRate(Corridor.USD_NGN))
                .isInstanceOf(PayBridgeException.class)
                .hasMessageContaining("No exchange rate")
                .extracting(e -> ((PayBridgeException) e).getErrorCode()).isEqualTo(ErrorCode.RATE_UNAVAILABLE);
    }

    @Test
    void aRateOlderThanTheLimitIsUnavailable() {
        when(store.latestActive(Corridor.USD_NGN)).thenReturn(Optional.of(stored("1500", NOW.minus(Duration.ofMinutes(5)).minusSeconds(1))));

        assertThatThrownBy(() -> service.currentRate(Corridor.USD_NGN))
                .hasMessageContaining("too old")
                .extracting(e -> ((PayBridgeException) e).getErrorCode()).isEqualTo(ErrorCode.RATE_UNAVAILABLE);
    }

    @Test
    void aRateExactlyAtTheLimitIsStillUsed() {
        when(store.latestActive(Corridor.USD_NGN)).thenReturn(Optional.of(stored("1500", NOW.minus(Duration.ofMinutes(5)))));
        when(store.spreadBps(Corridor.USD_NGN)).thenReturn(100);

        assertThat(service.currentRate(Corridor.USD_NGN).midRate()).isEqualByComparingTo("1500");
    }

    @Test
    void aFreshRateIsReturnedWithTheSpreadApplied() {
        when(store.latestActive(Corridor.USD_NGN)).thenReturn(Optional.of(stored("1500", NOW.minusSeconds(30))));
        when(store.spreadBps(Corridor.USD_NGN)).thenReturn(250);

        CurrentRate rate = service.currentRate(Corridor.USD_NGN);

        assertThat(rate.corridor()).isEqualTo(Corridor.USD_NGN);
        assertThat(rate.midRate()).isEqualByComparingTo("1500");
        assertThat(rate.spreadBps()).isEqualTo(250);
        assertThat(rate.customerRate()).isEqualByComparingTo("1462.5");
        assertThat(rate.source()).isEqualTo("test");
        assertThat(rate.asOf()).isEqualTo(NOW.minusSeconds(30));
    }

    @Test
    void whenTheSourceIsDownNothingIsStoredAndTheFailureIsCounted() {
        when(provider.fetch(Corridor.USD_NGN)).thenThrow(new RateProviderUnavailableException("down"));

        RefreshOutcome outcome = service.refresh(Corridor.USD_NGN);

        assertThat(outcome).isEqualTo(RefreshOutcome.SOURCE_DOWN);
        verify(store, never()).record(any(), any());
        assertThat(meters.counter("paybridge.fx.refresh", "result", "source_down").count()).isEqualTo(1.0);
    }

    @Test
    void aFetchedRateIsHandedToTheStoreAndItsOutcomeIsCounted() {
        ProviderRate reported = new ProviderRate(new BigDecimal("1510"), "fake", NOW);
        when(provider.fetch(Corridor.USD_NGN)).thenReturn(reported);
        when(store.record(Corridor.USD_NGN, reported)).thenReturn(RefreshOutcome.ACCEPTED);

        assertThat(service.refresh(Corridor.USD_NGN)).isEqualTo(RefreshOutcome.ACCEPTED);
        assertThat(meters.counter("paybridge.fx.refresh", "result", "accepted").count()).isEqualTo(1.0);
    }

    @Test
    void theAgeGaugeReportsMinusOneWhenThereIsNoRateAndTheAgeOtherwise() {
        when(store.latestActive(Corridor.USD_NGN)).thenReturn(Optional.empty());
        assertThat(meters.get("paybridge.fx.rate.age.seconds").tag("corridor", "USD_NGN").gauge().value()).isEqualTo(-1.0);

        when(store.latestActive(Corridor.USD_NGN)).thenReturn(Optional.of(stored("1500", NOW.minusSeconds(42))));
        assertThat(meters.get("paybridge.fx.rate.age.seconds").tag("corridor", "USD_NGN").gauge().value()).isEqualTo(42.0);
    }
}
