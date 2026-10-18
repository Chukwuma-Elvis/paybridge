package com.academy.paybridge.fx.web;

import com.academy.paybridge.fx.api.Conversion;
import com.academy.paybridge.fx.api.Corridor;
import com.academy.paybridge.fx.api.CurrentRate;
import com.academy.paybridge.fx.api.FxApi;
import com.academy.paybridge.fx.api.Quote;
import com.academy.paybridge.shared.config.AccessLogFilter;
import com.academy.paybridge.shared.web.IdempotencyKeyHeader;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** What a client sees: the rate in force for a corridor (margin already taken off) and quotes that lock a rate. */
@RestController
@RequestMapping("/api/fx")
public class FxController {

    private final FxApi fxApi;

    public FxController(FxApi fxApi) {
        this.fxApi = fxApi;
    }

    @GetMapping("/rates/{corridor}")
    public CurrentRate current(@PathVariable Corridor corridor) {
        return fxApi.currentRate(corridor);
    }

    /** 201 when a new quote is issued, 200 when the same Idempotency-Key replays an earlier one. */
    @PostMapping("/quotes")
    public ResponseEntity<Quote> createQuote(@Valid @RequestBody FxRequests.CreateQuote request,
                                             @RequestHeader(value = IdempotencyKeyHeader.NAME, required = false) String key,
                                             HttpServletRequest http) {
        String caller = String.valueOf(http.getAttribute(AccessLogFilter.CLIENT_ATTRIBUTE));
        FxApi.QuoteIssued issued = fxApi.createQuote(request.corridor(), request.sellMinor(), caller,
                IdempotencyKeyHeader.require(key));
        return ResponseEntity.status(issued.created() ? HttpStatus.CREATED : HttpStatus.OK).body(issued.quote());
    }

    /** The conversion made for a quote (both ledger legs), or 404 if the quote has not been converted. */
    @GetMapping("/conversions/{quoteId}")
    public Conversion conversion(@PathVariable UUID quoteId) {
        return fxApi.findConversion(quoteId)
                .orElseThrow(() -> new com.academy.paybridge.shared.exception.ResourceNotFoundException("Conversion for quote", quoteId));
    }

    @GetMapping("/quotes/{id}")
    public Quote quote(@PathVariable UUID id) {
        return fxApi.getQuote(id);
    }
}
