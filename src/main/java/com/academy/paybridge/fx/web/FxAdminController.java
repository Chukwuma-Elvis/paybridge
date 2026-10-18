package com.academy.paybridge.fx.web;

import com.academy.paybridge.fx.api.Corridor;
import com.academy.paybridge.fx.domain.FxSpread;
import com.academy.paybridge.fx.service.FxRateService;
import com.academy.paybridge.fx.service.RefreshOutcome;
import com.academy.paybridge.shared.config.AccessLogFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Managing the exchange-rate feed. Everything under {@code /api/admin} needs an ADMIN key (the security filters enforce that before
 * a request gets here). The caller's key NAME is recorded against every change, never the key.
 */
@RestController
@RequestMapping("/api/admin/fx")
public class FxAdminController {

    private final FxRateService fxRateService;

    public FxAdminController(FxRateService fxRateService) {
        this.fxRateService = fxRateService;
    }

    @GetMapping("/rates")
    public List<RateResponse> rates(@RequestParam(defaultValue = "USD_NGN") Corridor corridor,
                                    @RequestParam(defaultValue = "20") int limit) {
        return fxRateService.recent(corridor, limit).stream().map(RateResponse::from).toList();
    }

    /** Fetches a rate from the source right now (the timer does this on its own schedule). */
    @PostMapping("/refresh")
    public Map<String, String> refresh(@Valid @RequestBody FxRequests.Refresh request) {
        RefreshOutcome outcome = fxRateService.refresh(request.corridor());
        return Map.of("outcome", outcome.name());
    }

    /** Accepts a held rate. */
    @PostMapping("/rates/{id}/approve")
    public RateResponse approve(@PathVariable UUID id, HttpServletRequest request) {
        return RateResponse.from(fxRateService.approve(id, caller(request)));
    }

    /** Sets a rate by hand, for example when the source is down. */
    @PostMapping("/rates")
    public ResponseEntity<RateResponse> setManually(@Valid @RequestBody FxRequests.ManualRate body, HttpServletRequest request) {
        RateResponse created = RateResponse.from(fxRateService.setManually(body.corridor(), body.midRate(), body.note(), caller(request)));
        return ResponseEntity.created(URI.create("/api/admin/fx/rates/" + created.id())).body(created);
    }

    @PutMapping("/spread")
    public Map<String, Object> setSpread(@Valid @RequestBody FxRequests.Spread body, HttpServletRequest request) {
        FxSpread saved = fxRateService.setSpread(body.corridor(), body.spreadBps(), caller(request));
        return Map.of("corridor", saved.getCorridor(), "spreadBps", saved.getSpreadBps(), "setBy", saved.getSetBy());
    }

    private static String caller(HttpServletRequest request) {
        Object name = request.getAttribute(AccessLogFilter.CLIENT_ATTRIBUTE);
        return name == null ? "unknown" : name.toString();
    }
}
