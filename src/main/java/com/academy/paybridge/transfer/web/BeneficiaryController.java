package com.academy.paybridge.transfer.web;

import com.academy.paybridge.transfer.client.ResolvedAccount;
import com.academy.paybridge.transfer.service.BeneficiaryService;
import com.academy.paybridge.transfer.service.BeneficiaryView;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/beneficiaries")
public class BeneficiaryController {

    private final BeneficiaryService beneficiaryService;

    public BeneficiaryController(BeneficiaryService beneficiaryService) {
        this.beneficiaryService = beneficiaryService;
    }

    /** Name enquiry: shows whose account this is before the customer commits to saving or paying it. */
    @PostMapping("/resolve")
    public ResolvedAccount resolve(@Valid @RequestBody BeneficiaryRequests.Resolve request) {
        return beneficiaryService.resolve(request.bankCode(), request.accountNumber());
    }

    @PostMapping
    public ResponseEntity<BeneficiaryView> create(@Valid @RequestBody BeneficiaryRequests.Create request) {
        BeneficiaryView saved = beneficiaryService.create(request.customerId(), request.bankCode(), request.accountNumber());
        return ResponseEntity.created(URI.create("/api/beneficiaries/" + saved.id())).body(saved);
    }

    @GetMapping
    public List<BeneficiaryView> list(@RequestParam UUID customerId) {
        return beneficiaryService.listForCustomer(customerId);
    }
}
