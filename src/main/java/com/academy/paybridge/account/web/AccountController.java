package com.academy.paybridge.account.web;

import com.academy.paybridge.account.api.AccountSummary;
import com.academy.paybridge.account.service.AccountService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/accounts")
public class AccountController {

    private final AccountService accountService;

    public AccountController(AccountService accountService) {
        this.accountService = accountService;
    }

    @PostMapping
    public ResponseEntity<AccountResponse> open(@Valid @RequestBody OpenAccountRequest request) {
        AccountSummary opened = accountService.openCustomerAccount(request.customerId(), request.currencyOrDefault());
        return ResponseEntity
                .created(URI.create("/api/accounts/" + opened.id()))
                .body(AccountResponse.from(opened));
    }

    @GetMapping("/{id}")
    public AccountResponse get(@PathVariable UUID id) {
        return AccountResponse.from(accountService.getAccount(id));
    }

    @GetMapping("/by-number/{accountNumber}")
    public AccountResponse getByNumber(@PathVariable String accountNumber) {
        return AccountResponse.from(accountService.getByAccountNumber(accountNumber));
    }

    @GetMapping
    public List<AccountResponse> listForCustomer(@RequestParam UUID customerId) {
        return accountService.listForCustomer(customerId).stream().map(AccountResponse::from).toList();
    }
}
