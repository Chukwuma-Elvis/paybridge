package com.academy.paybridge.remittance.web;

import com.academy.paybridge.remittance.api.RemittanceApi;
import com.academy.paybridge.remittance.api.RemittanceView;
import com.academy.paybridge.remittance.service.RemittanceAudit;
import com.academy.paybridge.remittance.service.RemittanceProcessor;
import com.academy.paybridge.remittance.service.ReviewService;
import com.academy.paybridge.shared.config.AccessLogFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** The operator's desk for remittances. Everything under {@code /api/admin} needs an ADMIN key; decisions are recorded against the key's NAME. */
@RestController
@RequestMapping("/api/admin/remittances")
public class RemittanceAdminController {

    private final RemittanceApi remittances;
    private final ReviewService reviews;
    private final RemittanceAudit audit;
    private final RemittanceProcessor processor;

    public RemittanceAdminController(RemittanceApi remittances, ReviewService reviews, RemittanceAudit audit,
                                     RemittanceProcessor processor) {
        this.remittances = remittances;
        this.reviews = reviews;
        this.audit = audit;
        this.processor = processor;
    }

    /** Every status the remittance has been through, oldest first. */
    @GetMapping("/{id}/history")
    public List<String> history(@PathVariable UUID id) {
        remittances.get(id);   // 404 if it does not exist
        return audit.history(id);
    }

    /** Decides the open screening cases of a remittance under review (with a written reason) and resumes or stops it at once. */
    @PostMapping("/{id}/review")
    public RemittanceView review(@PathVariable UUID id, @Valid @RequestBody RemittanceRequests.Review body, HttpServletRequest http) {
        String decidedBy = String.valueOf(http.getAttribute(AccessLogFilter.CLIENT_ATTRIBUTE));
        return reviews.decide(id, body.decision(), body.reason(), decidedBy);
    }

    /** Runs one pass of the engine now (the timer does this on its own every few seconds). */
    @PostMapping("/process")
    public Map<String, Integer> process() {
        return Map.of("handled", processor.runOnce());
    }
}
