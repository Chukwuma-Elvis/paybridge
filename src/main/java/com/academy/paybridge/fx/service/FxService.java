package com.academy.paybridge.fx.service;

import com.academy.paybridge.fx.api.Conversion;
import com.academy.paybridge.fx.api.Corridor;
import com.academy.paybridge.fx.api.CurrentRate;
import com.academy.paybridge.fx.api.FxApi;
import com.academy.paybridge.fx.api.Quote;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.UUID;

/** The one door other modules use to reach the fx module: it only hands each question to the right service. */
@Service
class FxService implements FxApi {

    private final FxRateService rates;
    private final QuoteService quotes;
    private final ConversionService conversions;

    FxService(FxRateService rates, QuoteService quotes, ConversionService conversions) {
        this.rates = rates;
        this.quotes = quotes;
        this.conversions = conversions;
    }

    @Override
    public CurrentRate currentRate(Corridor corridor) {
        return rates.currentRate(corridor);
    }

    @Override
    public QuoteIssued createQuote(Corridor corridor, long sellMinor, String requestedBy, String idempotencyKey) {
        return quotes.create(corridor, sellMinor, requestedBy, idempotencyKey);
    }

    @Override
    public Quote consumeQuote(UUID quoteId, UUID consumerId) {
        return quotes.consume(quoteId, consumerId);
    }

    @Override
    public Quote getQuote(UUID quoteId) {
        return quotes.get(quoteId);
    }

    @Override
    public Conversion convert(UUID quoteId, UUID usdSourceAccountId, UUID ngnTargetAccountId) {
        return conversions.convert(quoteId, usdSourceAccountId, ngnTargetAccountId);
    }

    @Override
    public Conversion reverseConversion(UUID quoteId, String reason) {
        return conversions.reverse(quoteId, reason);
    }

    @Override
    public Optional<Conversion> findConversion(UUID quoteId) {
        return conversions.find(quoteId);
    }
}
