package com.academy.paybridge.compliance.api;

import java.time.LocalDate;
import java.util.List;

/**
 * One listed person or business, as a screening source reports it.
 *
 * @param listName name of the list it came from
 * @param entryId  the source's own id for the entry
 * @param names    the main name first, then known aliases
 * @param country  two-letter code, or null
 * @param birth    date of birth, or null
 */
public record WatchlistEntry(String listName, String entryId, List<String> names, String country, LocalDate birth) {
}
