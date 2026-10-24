package com.academy.paybridge.compliance.service;

import com.academy.paybridge.compliance.api.Party;
import com.academy.paybridge.compliance.api.ScreeningOutcome;
import com.academy.paybridge.compliance.api.WatchlistEntry;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The rule that turns "how alike are these names" into CLEAR, REVIEW or BLOCK. A pure calculation, so it is tested without a database.
 * <ul>
 *   <li>A <b>corroborator</b> is anything besides the name that also matches: the country, the date of birth.</li>
 *   <li>Similarity at or above the block threshold <b>and</b> at least one corroborator: BLOCK.</li>
 *   <li>Similarity at or above the review threshold: REVIEW. Note this includes a near-perfect name match with nothing to back it
 *       up: that is ambiguous, and an ambiguous hit is NEVER cleared automatically.</li>
 *   <li>A lower similarity (but at least the corroborated-review level) with BOTH corroborators: REVIEW.</li>
 *   <li>Anything else is CLEAR: a weak name match with nothing to support it is a likely false positive.</li>
 * </ul>
 */
final class ScreeningPolicy {

    private ScreeningPolicy() {
    }

    /** One listed entry that came close to the party. */
    record Hit(String listName, String entryId, double similarity, int corroborators) {

        String describe() {
            return String.format(Locale.ROOT, "%s/%s similarity=%.2f corroborators=%d", listName, entryId, similarity, corroborators);
        }
    }

    record Assessment(ScreeningOutcome outcome, List<Hit> hits) {

        String details() {
            return hits.isEmpty() ? "no close entries" : String.join("; ", hits.stream().map(Hit::describe).toList());
        }
    }

    static Assessment assess(Party party, List<WatchlistEntry> candidates, AmlProperties.Screening cfg) {
        ScreeningOutcome worst = ScreeningOutcome.CLEAR;
        List<Hit> hits = new ArrayList<>();
        for (WatchlistEntry entry : candidates) {
            double similarity = entry.names().stream().mapToDouble(name -> NameSimilarity.between(party.fullName(), name)).max().orElse(0);
            int corroborators = 0;
            if (party.country() != null && entry.country() != null && party.country().equalsIgnoreCase(entry.country())) {
                corroborators++;
            }
            if (party.dateOfBirth() != null && party.dateOfBirth().equals(entry.birth())) {
                corroborators++;
            }
            ScreeningOutcome outcome = outcomeFor(similarity, corroborators, cfg);
            if (outcome != ScreeningOutcome.CLEAR) {
                hits.add(new Hit(entry.listName(), entry.entryId(), similarity, corroborators));
                if (outcome == ScreeningOutcome.BLOCK) {
                    worst = ScreeningOutcome.BLOCK;
                } else if (worst == ScreeningOutcome.CLEAR) {
                    worst = ScreeningOutcome.REVIEW;
                }
            }
        }
        return new Assessment(worst, hits);
    }

    static ScreeningOutcome outcomeFor(double similarity, int corroborators, AmlProperties.Screening cfg) {
        if (similarity >= cfg.blockThreshold() && corroborators >= 1) {
            return ScreeningOutcome.BLOCK;
        }
        if (similarity >= cfg.reviewThreshold()) {
            return ScreeningOutcome.REVIEW;
        }
        if (similarity >= cfg.corroboratedReview() && corroborators >= 2) {
            return ScreeningOutcome.REVIEW;
        }
        return ScreeningOutcome.CLEAR;
    }
}
