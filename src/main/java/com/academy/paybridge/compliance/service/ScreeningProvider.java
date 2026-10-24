package com.academy.paybridge.compliance.service;

import com.academy.paybridge.compliance.api.Party;
import com.academy.paybridge.compliance.api.WatchlistEntry;

import java.util.List;

/**
 * The seam between PayBridge and wherever watch-list data comes from (a national list, a commercial vendor, a UN list import).
 * It only supplies CANDIDATES; deciding how close is close enough is PayBridge's own, configurable rule
 * ({@link ScreeningService}), so changing the data source never changes the policy.
 */
public interface ScreeningProvider {

    /** Entries worth comparing with this party. A simple source may return its whole list. */
    List<WatchlistEntry> candidatesFor(Party party);

    default List<WatchlistEntry> allEntries() {
        return candidatesFor(new Party("", null, null));
    }

    default WatchlistEntry addEntry(WatchlistEntry entry) {
        throw new UnsupportedOperationException("Adding watchlist entries is not supported by this provider");
    }

    default void reset() {
    }
}
