package com.academy.paybridge.compliance.web;

import com.academy.paybridge.compliance.api.ComplianceApi;
import com.academy.paybridge.compliance.api.LimitStatus;
import com.academy.paybridge.compliance.api.WatchlistEntry;
import com.academy.paybridge.shared.exception.ErrorCode;
import com.academy.paybridge.shared.exception.PayBridgeException;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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

    /** Lists all active screening watchlist entries (built-in and dynamically added). */
    @GetMapping("/watchlist")
    public List<WatchlistEntry> listWatchlist() {
        return complianceApi.listWatchlist();
    }

    public record AddWatchlistRequest(
            String listName,
            String entryId,
            String name,
            List<String> names,
            String country,
            LocalDate birth) {

        public List<String> resolvedNames() {
            List<String> list = new ArrayList<>();
            if (names != null) {
                for (String n : names) {
                    if (n != null && !n.isBlank()) {
                        list.add(n.trim());
                    }
                }
            }
            if (name != null && !name.isBlank() && !list.contains(name.trim())) {
                list.add(0, name.trim());
            }
            return list;
        }
    }

    /** Adds a new candidate entry to the screening watchlist. */
    @PostMapping("/watchlist")
    public ResponseEntity<WatchlistEntry> addWatchlist(@Valid @RequestBody AddWatchlistRequest body) {
        List<String> names = body.resolvedNames();
        if (names.isEmpty()) {
            throw new PayBridgeException(ErrorCode.VALIDATION_FAILED, "At least one name is required for watchlist screening");
        }
        String country = body.country() != null && !body.country().isBlank() ? body.country().trim().toUpperCase(Locale.ROOT) : null;
        if (country != null && !country.matches("^[A-Z]{2}$")) {
            throw new PayBridgeException(ErrorCode.VALIDATION_FAILED, "Country code must be a 2-letter ISO code");
        }
        String listName = body.listName() != null && !body.listName().isBlank() ? body.listName().trim() : "CUSTOM-LIST";
        String entryId = body.entryId() != null && !body.entryId().isBlank()
                ? body.entryId().trim()
                : "CUST-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);

        WatchlistEntry entry = new WatchlistEntry(listName, entryId, names, country, body.birth());
        WatchlistEntry added = complianceApi.addWatchlistEntry(entry);
        return ResponseEntity.status(HttpStatus.CREATED).body(added);
    }

    /** Resets the watchlist back to built-in synthetic entries only. */
    @DeleteMapping("/watchlist")
    public ResponseEntity<Void> resetWatchlist() {
        complianceApi.resetWatchlist();
        return ResponseEntity.noContent().build();
    }
}
