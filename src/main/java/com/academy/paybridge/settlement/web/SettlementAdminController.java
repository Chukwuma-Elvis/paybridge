package com.academy.paybridge.settlement.web;

import com.academy.paybridge.settlement.service.BatchService;
import com.academy.paybridge.settlement.service.BreakService;
import com.academy.paybridge.settlement.service.SettlementProperties;
import com.academy.paybridge.settlement.service.SettlementViews.BatchLineView;
import com.academy.paybridge.settlement.service.SettlementViews.BatchReport;
import com.academy.paybridge.settlement.service.SettlementViews.BatchView;
import com.academy.paybridge.settlement.service.SettlementViews.BreakView;
import com.academy.paybridge.settlement.service.SettlementViews.Position;
import com.academy.paybridge.settlement.service.SettlementViews.StatementResult;
import com.academy.paybridge.settlement.service.StatementService;
import com.academy.paybridge.shared.config.AccessLogFilter;
import com.academy.paybridge.shared.exception.ErrorCode;
import com.academy.paybridge.shared.exception.PayBridgeException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.InputStream;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The settlement desk: close batches, follow their payment, load the partner's statement, work the breaks. Everything under
 * {@code /api/admin} needs an ADMIN key; every action is recorded against the key's NAME.
 */
@RestController
@RequestMapping("/api/admin/settlement")
public class SettlementAdminController {

    private final BatchService batches;
    private final StatementService statements;
    private final BreakService breaks;
    private final SettlementProperties properties;
    private final Clock clock;

    public SettlementAdminController(BatchService batches, StatementService statements, BreakService breaks,
                                     SettlementProperties properties, Clock clock) {
        this.batches = batches;
        this.statements = statements;
        this.breaks = breaks;
        this.properties = properties;
        this.clock = clock;
    }

    @GetMapping("/partners/{partner}/position")
    public Position position(@PathVariable String partner) {
        return batches.position(partner);
    }

    /** Freezes everything on the partner's account before the cut-off (default: now) into a new batch. 204 when there was nothing new. */
    @PostMapping("/partners/{partner}/close")
    public ResponseEntity<BatchView> close(@PathVariable String partner, @RequestParam(required = false) Instant cutoff) {
        Instant at = cutoff != null ? cutoff : clock.instant();
        return batches.close(partner, at).map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.noContent().build());
    }

    @GetMapping("/batches")
    public List<BatchView> list(@RequestParam(required = false) String partner, @RequestParam(defaultValue = "50") int limit) {
        return batches.list(partner, limit);
    }

    @GetMapping("/batches/{id}")
    public BatchView get(@PathVariable UUID id) {
        return batches.get(id);
    }

    @GetMapping("/batches/{id}/lines")
    public List<BatchLineView> lines(@PathVariable UUID id) {
        return batches.lines(id);
    }

    @GetMapping("/batches/{id}/report")
    public BatchReport report(@PathVariable UUID id) {
        return batches.report(id);
    }

    @PostMapping("/batches/{id}/instruct")
    public BatchView instruct(@PathVariable UUID id) {
        return batches.instruct(id);
    }

    @PostMapping("/batches/{id}/confirm")
    public BatchView confirm(@PathVariable UUID id) {
        return batches.confirm(id);
    }

    public record Note(@NotBlank(message = "a note is required") @Size(max = 500, message = "must be at most 500 characters") String note) {
    }

    @PostMapping("/batches/{id}/dispute")
    public BatchView dispute(@PathVariable UUID id, @Valid @RequestBody Note body) {
        return batches.dispute(id, body.note());
    }

    /**
     * Loads the partner's statement (a CSV: reference,amount,currency,date) for the batch. The body is read exactly as sent and never more
     * than the size limit. 201 when loaded, 200 when this very file was already loaded.
     */
    @PostMapping(value = "/batches/{id}/statement", consumes = {"text/csv", "text/plain", "application/octet-stream"})
    public ResponseEntity<StatementResult> uploadStatement(@PathVariable UUID id, HttpServletRequest request) throws IOException {
        int max = properties.statement().maxBytes();
        if (request.getContentLengthLong() > max) {
            throw tooLarge(max);
        }
        byte[] body;
        try (InputStream in = request.getInputStream()) {
            body = in.readNBytes(max + 1);
        }
        if (body.length > max) {
            throw tooLarge(max);
        }
        StatementResult result = statements.upload(id, body, uploadedBy(request));
        return ResponseEntity.status(result.duplicate() ? HttpStatus.OK : HttpStatus.CREATED).body(result);
    }

    @GetMapping("/batches/{id}/breaks")
    public List<BreakView> breaks(@PathVariable UUID id, @RequestParam(required = false) String status) {
        return breaks.list(id, status);
    }

    @GetMapping("/breaks/{id}")
    public BreakView getBreak(@PathVariable UUID id) {
        return breaks.get(id);
    }

    public record Owner(@NotBlank(message = "an owner is required") @Size(max = 100, message = "must be at most 100 characters") String owner) {
    }

    @PostMapping("/breaks/{id}/assign")
    public BreakView assign(@PathVariable UUID id, @Valid @RequestBody Owner body) {
        return breaks.assign(id, body.owner());
    }

    public record Resolution(
            @NotBlank(message = "a written note is required") @Size(max = 500, message = "must be at most 500 characters") String note,
            boolean adjust) {
    }

    /** Resolves a break with a mandatory note; with {@code adjust} also posts the correcting ledger entry. */
    @PostMapping("/breaks/{id}/resolve")
    public BreakView resolve(@PathVariable UUID id, @Valid @RequestBody Resolution body, HttpServletRequest request) {
        return breaks.resolve(id, body.note(), body.adjust(), uploadedBy(request));
    }

    private static String uploadedBy(HttpServletRequest request) {
        return String.valueOf(request.getAttribute(AccessLogFilter.CLIENT_ATTRIBUTE));
    }

    private static PayBridgeException tooLarge(int max) {
        return new PayBridgeException(ErrorCode.PAYLOAD_TOO_LARGE, "The statement is larger than " + max + " bytes");
    }
}
