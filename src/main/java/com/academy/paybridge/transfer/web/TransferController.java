package com.academy.paybridge.transfer.web;

import com.academy.paybridge.transfer.api.TransferApi;
import com.academy.paybridge.transfer.service.InternalTransferCommand;
import com.academy.paybridge.transfer.service.TransferResult;
import com.academy.paybridge.transfer.service.TransferService;
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

import java.net.URI;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/transfers")
public class TransferController {

    private static final int MAX_LIMIT = 200;

    private final TransferService transferService;
    private final TransferApi transferApi;

    public TransferController(TransferService transferService, TransferApi transferApi) {
        this.transferService = transferService;
        this.transferApi = transferApi;
    }

    @PostMapping("/internal")
    public ResponseEntity<TransferResponse> transferInternal(
            @RequestHeader(value = IdempotencyHeader.NAME, required = false) String idempotencyKey,
            @Valid @RequestBody InternalTransferRequest request) {

        String key = IdempotencyHeader.require(idempotencyKey);
        TransferResult result = transferService.transferInternal(new InternalTransferCommand(
                request.sourceAccountId(), request.destinationAccountNumber(),
                request.amountMinor(), normalise(request.narration())), key);

        return respond(result);
    }

    @GetMapping("/{id}")
    public TransferResponse get(@PathVariable UUID id) {
        return TransferResponse.from(transferApi.getTransfer(id));
    }

    @GetMapping
    public List<TransferResponse> listForAccount(@RequestParam UUID accountId,
                                                 @RequestParam(defaultValue = "50") int limit) {
        int bounded = Math.max(1, Math.min(limit, MAX_LIMIT));
        return transferApi.listForAccount(accountId, bounded).stream().map(TransferResponse::from).toList();
    }

    static ResponseEntity<TransferResponse> respond(TransferResult result) {
        TransferResponse body = TransferResponse.from(result.transfer());
        if (result.replayed()) {
            return ResponseEntity.ok().header("Idempotent-Replayed", "true").body(body);
        }
        return ResponseEntity.status(HttpStatus.CREATED)
                .location(URI.create("/api/transfers/" + body.id()))
                .body(body);
    }

    static String normalise(String narration) {
        return narration == null || narration.isBlank() ? null : narration.trim();
    }
}
