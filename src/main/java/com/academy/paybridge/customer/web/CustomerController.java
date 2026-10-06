package com.academy.paybridge.customer.web;

import com.academy.paybridge.customer.api.CustomerSummary;
import com.academy.paybridge.customer.service.CustomerService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.UUID;

@RestController
@RequestMapping("/api/customers")
public class CustomerController {

    private final CustomerService customerService;

    public CustomerController(CustomerService customerService) {
        this.customerService = customerService;
    }

    @PostMapping
    public ResponseEntity<CustomerResponse> create(@Valid @RequestBody CreateCustomerRequest request) {
        CustomerSummary created = customerService.register(
                request.firstName(), request.lastName(), request.email(), request.phone());
        return ResponseEntity
                .created(URI.create("/api/customers/" + created.id()))
                .body(CustomerResponse.from(created));
    }

    @GetMapping("/{id}")
    public CustomerResponse get(@PathVariable UUID id) {
        return CustomerResponse.from(customerService.getCustomer(id));
    }

    @PatchMapping("/{id}/kyc-tier")
    public CustomerResponse changeKycTier(@PathVariable UUID id, @Valid @RequestBody UpdateKycTierRequest request) {
        return CustomerResponse.from(customerService.changeKycTier(id, request.kycTier()));
    }
}
