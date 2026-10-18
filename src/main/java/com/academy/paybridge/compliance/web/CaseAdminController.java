package com.academy.paybridge.compliance.web;

import com.academy.paybridge.compliance.api.CaseDecision;
import com.academy.paybridge.compliance.api.CaseStatus;
import com.academy.paybridge.compliance.api.CaseView;
import com.academy.paybridge.compliance.api.ComplianceApi;
import com.academy.paybridge.shared.config.AccessLogFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * The compliance reviewer's desk: the open screening cases and the decision on each. Everything under {@code /api/admin} needs an
 * ADMIN key; the decision is recorded against the key's NAME (never the key itself).
 */
@RestController
@RequestMapping("/api/admin/compliance/cases")
public class CaseAdminController {

    private final ComplianceApi complianceApi;

    public CaseAdminController(ComplianceApi complianceApi) {
        this.complianceApi = complianceApi;
    }

    @GetMapping
    public List<CaseView> list(@RequestParam(defaultValue = "OPEN") CaseStatus status,
                               @RequestParam(defaultValue = "50") int limit) {
        return complianceApi.listCases(status, limit);
    }

    @GetMapping("/{id}")
    public CaseView get(@PathVariable UUID id) {
        return complianceApi.getCase(id);
    }

    public record Decision(
            @NotNull(message = "must be APPROVE or REJECT") CaseDecision decision,
            @NotBlank(message = "a written reason is required") @Size(max = 500, message = "must be at most 500 characters") String reason) {
    }

    /** Repeating the same decision is harmless; the opposite decision on a decided case is a 409. */
    @PostMapping("/{id}/decision")
    public CaseView decide(@PathVariable UUID id, @Valid @RequestBody Decision body, HttpServletRequest http) {
        String decidedBy = String.valueOf(http.getAttribute(AccessLogFilter.CLIENT_ATTRIBUTE));
        return complianceApi.decideCase(id, body.decision(), body.reason(), decidedBy);
    }
}
