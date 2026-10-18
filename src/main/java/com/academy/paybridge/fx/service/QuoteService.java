package com.academy.paybridge.fx.service;

import com.academy.paybridge.fx.api.Corridor;
import com.academy.paybridge.fx.api.CurrentRate;
import com.academy.paybridge.fx.api.FxApi.QuoteIssued;
import com.academy.paybridge.fx.api.Quote;
import com.academy.paybridge.fx.api.QuoteStatus;
import com.academy.paybridge.fx.domain.FxQuote;
import com.academy.paybridge.fx.repository.FxQuoteRepository;
import com.academy.paybridge.shared.exception.ErrorCode;
import com.academy.paybridge.shared.exception.PayBridgeException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Issues and uses quotes. A quote freezes a rate, a fee and a result for a short time; using it is a one-shot, race-free step.
 */
@Service
public class QuoteService {

    private final FxRateService rates;
    private final FxQuoteRepository quotes;
    private final FxProperties properties;
    private final Clock clock;

    QuoteService(FxRateService rates, FxQuoteRepository quotes, FxProperties properties, Clock clock) {
        this.rates = rates;
        this.quotes = quotes;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional
    public QuoteIssued create(Corridor corridor, long sellMinor, String requestedBy, String idempotencyKey) {
        FxProperties.Quotes limits = properties.quotes();
        if (sellMinor < limits.minSellMinor() || sellMinor > limits.maxSellMinor()) {
            throw new PayBridgeException(ErrorCode.VALIDATION_FAILED, "The amount must be between " + limits.minSellMinor()
                    + " and " + limits.maxSellMinor() + " " + corridor.sell().getCurrencyCode() + " minor units");
        }
        String hash = hash(corridor, sellMinor);

        var existing = quotes.findByRequestedByAndIdempotencyKey(requestedBy, idempotencyKey);
        if (existing.isPresent()) {
            return replay(existing.get(), hash);
        }

        CurrentRate rate = rates.currentRate(corridor);
        long fee = ConversionCalculator.fee(sellMinor, limits.feeBps(), limits.minFeeMinor());
        if (fee >= sellMinor) {
            throw new PayBridgeException(ErrorCode.VALIDATION_FAILED, "The amount is too small to cover the fee");
        }
        long buy = ConversionCalculator.convert(sellMinor - fee, rate.customerRate());
        if (buy <= 0) {
            throw new PayBridgeException(ErrorCode.VALIDATION_FAILED, "The amount is too small to convert");
        }

        UUID id = UUID.randomUUID();
        String reference = "QT-" + id.toString().replace("-", "").substring(0, 16).toUpperCase();
        int inserted = quotes.issueIfAbsent(id, reference, corridor.name(), sellMinor, fee, buy, rate.midRate(),
                rate.customerRate(), rate.spreadBps(), rate.source(), rate.asOf(), properties.quoteTtl().toSeconds(),
                requestedBy, idempotencyKey, hash);
        if (inserted == 0) {
            // a simultaneous request with the same key won the race; answer with its quote
            return replay(quotes.findByRequestedByAndIdempotencyKey(requestedBy, idempotencyKey).orElseThrow(), hash);
        }
        return new QuoteIssued(view(quotes.findById(id).orElseThrow()), true);
    }

    /** Succeeds at most once per quote; a retry by the same consumer returns the consumed quote. */
    @Transactional
    public Quote consume(UUID quoteId, UUID consumerId) {
        FxQuote quote = quotes.findById(quoteId).orElseThrow(() ->
                new PayBridgeException(ErrorCode.RESOURCE_NOT_FOUND, "Quote " + quoteId + " does not exist"));
        if (quotes.consume(quoteId, consumerId) == 1) {
            return view(quotes.findById(quoteId).orElseThrow());
        }
        FxQuote now = quotes.findById(quoteId).orElseThrow();
        if (now.getStatus() == QuoteStatus.CONSUMED) {
            if (consumerId.equals(now.getConsumedBy())) {
                return view(now);
            }
            throw new PayBridgeException(ErrorCode.INVALID_STATE, "Quote " + quote.getReference() + " has already been used");
        }
        if (now.getStatus() == QuoteStatus.CANCELLED) {
            throw new PayBridgeException(ErrorCode.INVALID_STATE, "Quote " + quote.getReference() + " was cancelled");
        }
        throw new PayBridgeException(ErrorCode.QUOTE_EXPIRED, "Quote " + quote.getReference() + " has expired; request a new one");
    }

    @Transactional(readOnly = true)
    public Quote get(UUID quoteId) {
        return view(quotes.findById(quoteId).orElseThrow(() ->
                new PayBridgeException(ErrorCode.RESOURCE_NOT_FOUND, "Quote " + quoteId + " does not exist")));
    }

    private QuoteIssued replay(FxQuote existing, String hash) {
        if (!existing.getRequestHash().equals(hash)) {
            throw new PayBridgeException(ErrorCode.IDEMPOTENCY_KEY_REUSED,
                    "This Idempotency-Key was already used for a different quote request");
        }
        return new QuoteIssued(view(existing), false);
    }

    private Quote view(FxQuote q) {
        QuoteStatus status = q.getStatus();
        if (status == QuoteStatus.OPEN && !q.getExpiresAt().isAfter(clock.instant())) {
            status = QuoteStatus.EXPIRED;
        }
        return new Quote(q.getId(), q.getReference(), q.getCorridor(), q.getSellMinor(), q.getFeeMinor(), q.getBuyMinor(),
                q.getMidRate(), q.getCustomerRate(), q.getSpreadBps(), q.getRateSource(), q.getRateAsOf(), q.getExpiresAt(),
                status, q.getConsumedBy());
    }

    private static String hash(Corridor corridor, long sellMinor) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((corridor.name() + "|" + sellMinor).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always available", e);
        }
    }
}
