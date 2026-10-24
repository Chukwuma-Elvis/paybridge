package com.academy.paybridge.compliance.service;

import com.academy.paybridge.compliance.api.Party;
import com.academy.paybridge.compliance.api.WatchlistEntry;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The built-in screening source for development and tests. The list is entirely SYNTHETIC: every name below is invented and no real
 * list is ever stored in this repository. Tests can add entries with {@link #add}.
 */
@Component
public class FakeScreeningProvider implements ScreeningProvider {

    private static final List<WatchlistEntry> BUILT_IN = List.of(
            new WatchlistEntry("SYNTHETIC-LIST", "SYN-001", List.of("Zorgon Testlist", "Z. Testlist"), "ZZ",
                    LocalDate.of(1970, 1, 1)),
            new WatchlistEntry("SYNTHETIC-LIST", "SYN-002", List.of("Alina Samplova"), "RU", LocalDate.of(1982, 6, 15)),
            new WatchlistEntry("SYNTHETIC-LIST", "SYN-003", List.of("Kwame Mockerson", "Kwame M. Mocker"), "GH", null));

    private final List<WatchlistEntry> added = new CopyOnWriteArrayList<>();

    @Override
    public List<WatchlistEntry> candidatesFor(Party party) {
        List<WatchlistEntry> all = new java.util.ArrayList<>(BUILT_IN);
        all.addAll(added);
        return all;
    }

    @Override
    public List<WatchlistEntry> allEntries() {
        List<WatchlistEntry> all = new java.util.ArrayList<>(BUILT_IN);
        all.addAll(added);
        return all;
    }

    @Override
    public WatchlistEntry addEntry(WatchlistEntry entry) {
        added.add(entry);
        return entry;
    }

    public void add(WatchlistEntry entry) {
        added.add(entry);
    }

    @Override
    public void reset() {
        added.clear();
    }
}
