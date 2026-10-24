package com.academy.paybridge.compliance.web;

import com.academy.paybridge.compliance.api.ComplianceApi;
import com.academy.paybridge.compliance.api.WatchlistEntry;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Admin endpoint for viewing and managing screening watchlist entries.
 */
@RestController
@RequestMapping("/api/admin/compliance/watchlist")
public class WatchlistAdminController {

    private final ComplianceController delegate;

    public WatchlistAdminController(ComplianceApi complianceApi) {
        this.delegate = new ComplianceController(complianceApi);
    }

    @GetMapping
    public List<WatchlistEntry> list() {
        return delegate.listWatchlist();
    }

    @PostMapping
    public ResponseEntity<WatchlistEntry> add(@Valid @RequestBody ComplianceController.AddWatchlistRequest body) {
        return delegate.addWatchlist(body);
    }

    @DeleteMapping
    public ResponseEntity<Void> reset() {
        return delegate.resetWatchlist();
    }
}
