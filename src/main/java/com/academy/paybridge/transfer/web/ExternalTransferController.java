package com.academy.paybridge.transfer.web;

import com.academy.paybridge.transfer.service.ExternalTransferCommand;
import com.academy.paybridge.transfer.service.ExternalTransferService;
import com.academy.paybridge.transfer.service.TransferResult;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

@RestController
@RequestMapping("/api/transfers")
public class ExternalTransferController {

    private final ExternalTransferService externalTransferService;

    public ExternalTransferController(ExternalTransferService externalTransferService) {
        this.externalTransferService = externalTransferService;
    }

    /**
     * Accepts the transfer and answers 202 Accepted: the money is on hold and the transfer is PENDING, but the bank
     * has not been paid yet. Poll {@code GET /api/transfers/{id}} (or wait for the notification) for the outcome.
     */
    @PostMapping("/external")
    public ResponseEntity<TransferResponse> transferExternal(
            @RequestHeader(value = IdempotencyHeader.NAME, required = false) String idempotencyKey,
            @Valid @RequestBody ExternalTransferRequest request) {

        String key = IdempotencyHeader.require(idempotencyKey);
        TransferResult result = externalTransferService.initiate(new ExternalTransferCommand(
                request.sourceAccountId(), request.beneficiaryId(), request.amountMinor(),
                TransferController.normalise(request.narration())), key);

        TransferResponse body = TransferResponse.from(result.transfer());
        if (result.replayed()) {
            return ResponseEntity.ok().header("Idempotent-Replayed", "true").body(body);
        }
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .location(URI.create("/api/transfers/" + body.id()))
                .body(body);
    }
}
