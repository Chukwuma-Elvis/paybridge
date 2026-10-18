package com.academy.paybridge.remittance.web;

import com.academy.paybridge.remittance.api.RemittanceApi;
import com.academy.paybridge.remittance.api.RemittanceView;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * What a client does with remittances: start one from a quote, and read back how it is going. Starting one needs an
 * {@code Idempotency-Key}, so a retry after a lost reply never makes a second remittance.
 */
@RestController
@RequestMapping("/api/remittances")
public class RemittanceController {

    private final RemittanceApi remittances;

    public RemittanceController(RemittanceApi remittances) {
        this.remittances = remittances;
    }

    /** 201 when a new remittance is created, 200 when the same Idempotency-Key replays an earlier one. */
    @PostMapping
    public ResponseEntity<RemittanceView> create(@Valid @RequestBody RemittanceRequests.Create request,
                                                 @RequestHeader(value = IdempotencyKeyHeader.NAME, required = false) String key,
                                                 HttpServletRequest http) {
        String caller = String.valueOf(http.getAttribute(AccessLogFilter.CLIENT_ATTRIBUTE));
        RemittanceApi.Created created = remittances.create(request.toCommand(), caller, IdempotencyKeyHeader.require(key));
        return ResponseEntity.status(created.created() ? HttpStatus.CREATED : HttpStatus.OK).body(created.remittance());
    }

    @GetMapping("/{id}")
    public RemittanceView get(@PathVariable UUID id) {
        return remittances.get(id);
    }

    /** A sender's remittances, newest first. */
    @GetMapping
    public List<RemittanceView> forSender(@RequestParam String partnerCode, @RequestParam String senderReference,
                                          @RequestParam(defaultValue = "20") int limit) {
        return remittances.listForSender(partnerCode, senderReference, limit);
    }
}
