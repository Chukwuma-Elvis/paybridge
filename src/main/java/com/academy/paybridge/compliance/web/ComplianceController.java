package com.academy.paybridge.compliance.web;

import com.academy.paybridge.compliance.api.ComplianceApi;
import com.academy.paybridge.compliance.api.LimitStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/compliance")
public class ComplianceController {

    private final ComplianceApi complianceApi;

    public ComplianceController(ComplianceApi complianceApi) {
        this.complianceApi = complianceApi;
    }

    /** What the customer may send, and how much of today's allowance is already used. */
    @GetMapping("/customers/{customerId}/limits")
    public LimitStatus limits(@PathVariable UUID customerId) {
        return complianceApi.limitStatus(customerId);
    }
}
