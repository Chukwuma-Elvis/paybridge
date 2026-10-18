package com.academy.paybridge.settlement.service;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Lines up the partner's statement against our own record of the same movements, in three passes:
 * <ol>
 *   <li><b>By reference.</b> The same reference and the same amount (within the tolerance) is a match. The same reference with a
 *       different amount is an AMOUNT_DIFFERS pair, decided only after every exact match has been taken, so a duplicated line cannot
 *       steal another line's partner. A reference that was already matched and appears again is a duplicate.</li>
 *   <li><b>By amount and date</b>, for statement lines that carry no reference: the same amount within the tolerance and a date within
 *       the date tolerance.</li>
 *   <li><b>In groups</b>, for statement lines that still have no match: one statement line that equals the sum of two to four of our
 *       lines (a partner often reports one total for several payments).</li>
 * </ol>
 * Whatever is left over is UNMATCHED on the side it belongs to. Nothing is ever dropped silently: every line on both sides ends
 * up in exactly one of the outcomes. A pure calculation, tested without a database.
 */
final class StatementMatcher {

    /** One of our lines, with its amount in the statement's sign (what the partner holds for us is positive). */
    record Ours(UUID id, String reference, long amount, LocalDate date) {
    }

    record Theirs(UUID id, int rowNumber, String reference, long amount, LocalDate date) {
    }

    /** A statement line and the reference-sharing line of ours whose amount differs. */
    record Difference(UUID theirs, UUID ours) {
    }

    /**
     * @param matched          statement line to the line (or, for a grouped match, lines) of ours it accounts for
     * @param differences      pairs that share a reference but not an amount
     * @param unmatchedOurs    our lines the statement does not mention
     * @param unmatchedTheirs  statement lines we cannot account for, with the reason
     */
    record Result(Map<UUID, List<UUID>> matched, List<Difference> differences, List<UUID> unmatchedOurs,
                  Map<UUID, String> unmatchedTheirs) {
    }

    private static final int MAX_GROUP_SIZE = 4;
    private static final int MAX_GROUP_POOL = 25;

    private StatementMatcher() {
    }

    static Result match(List<Ours> ours, List<Theirs> theirs, long amountTolerance, int dateToleranceDays) {
        Map<UUID, List<UUID>> matched = new LinkedHashMap<>();
        List<Difference> differences = new ArrayList<>();
        Map<UUID, String> unmatchedTheirs = new LinkedHashMap<>();
        Set<UUID> usedOurs = new HashSet<>();
        Set<UUID> handledTheirs = new HashSet<>();

        // ---- pass 1a: same reference and the same amount
        for (Theirs t : theirs) {
            if (t.reference() == null) {
                continue;
            }
            Ours best = null;
            for (Ours o : ours) {
                if (usedOurs.contains(o.id()) || !t.reference().equals(o.reference())) {
                    continue;
                }
                long gap = Math.abs(o.amount() - t.amount());
                if (gap <= amountTolerance && (best == null || gap < Math.abs(best.amount() - t.amount()))) {
                    best = o;
                }
            }
            if (best != null) {
                matched.put(t.id(), List.of(best.id()));
                usedOurs.add(best.id());
                handledTheirs.add(t.id());
            }
        }

        // ---- pass 1b: same reference, different amount; or a reference we cannot place
        for (Theirs t : theirs) {
            if (t.reference() == null || handledTheirs.contains(t.id())) {
                continue;
            }
            Ours candidate = null;
            for (Ours o : ours) {
                if (!usedOurs.contains(o.id()) && t.reference().equals(o.reference())) {
                    candidate = o;
                    break;
                }
            }
            if (candidate != null) {
                differences.add(new Difference(t.id(), candidate.id()));
                usedOurs.add(candidate.id());
            } else if (ours.stream().anyMatch(o -> t.reference().equals(o.reference()))) {
                unmatchedTheirs.put(t.id(), "Duplicate line: this reference was already matched");
            } else {
                unmatchedTheirs.put(t.id(), "The reference is not in our records for this batch");
            }
            handledTheirs.add(t.id());
        }

        // ---- pass 2: lines without a reference, by amount and date
        for (Theirs t : theirs) {
            if (t.reference() != null) {
                continue;
            }
            Ours best = null;
            long bestDays = Long.MAX_VALUE;
            for (Ours o : ours) {
                if (usedOurs.contains(o.id()) || Math.abs(o.amount() - t.amount()) > amountTolerance) {
                    continue;
                }
                long days = Math.abs(ChronoUnit.DAYS.between(o.date(), t.date()));
                if (days <= dateToleranceDays && days < bestDays) {
                    best = o;
                    bestDays = days;
                }
            }
            if (best != null) {
                matched.put(t.id(), List.of(best.id()));
                usedOurs.add(best.id());
                handledTheirs.add(t.id());
            }
        }

        // ---- pass 3: lines without a reference that equal the sum of several of ours
        for (Theirs t : theirs) {
            if (t.reference() != null || handledTheirs.contains(t.id())) {
                continue;
            }
            List<Ours> pool = new ArrayList<>();
            for (Ours o : ours) {
                if (!usedOurs.contains(o.id()) && Math.abs(ChronoUnit.DAYS.between(o.date(), t.date())) <= dateToleranceDays
                        && pool.size() < MAX_GROUP_POOL) {
                    pool.add(o);
                }
            }
            List<Ours> group = findGroup(pool, t.amount(), amountTolerance);
            if (group != null) {
                List<UUID> ids = new ArrayList<>();
                for (Ours o : group) {
                    ids.add(o.id());
                    usedOurs.add(o.id());
                }
                matched.put(t.id(), ids);
                handledTheirs.add(t.id());
            }
        }

        for (Theirs t : theirs) {
            if (!handledTheirs.contains(t.id())) {
                unmatchedTheirs.put(t.id(), "No line of ours accounts for this");
            }
        }
        List<UUID> unmatchedOurs = new ArrayList<>();
        for (Ours o : ours) {
            if (!usedOurs.contains(o.id())) {
                unmatchedOurs.add(o.id());
            }
        }
        return new Result(matched, differences, unmatchedOurs, unmatchedTheirs);
    }

    /** A set of two to four lines whose amounts add up to the target, or null. */
    private static List<Ours> findGroup(List<Ours> pool, long target, long tolerance) {
        for (int size = 2; size <= MAX_GROUP_SIZE; size++) {
            List<Ours> found = search(pool, 0, size, 0, target, tolerance, new ArrayList<>());
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private static List<Ours> search(List<Ours> pool, int from, int left, long sum, long target, long tolerance, List<Ours> chosen) {
        if (left == 0) {
            return Math.abs(sum - target) <= tolerance ? new ArrayList<>(chosen) : null;
        }
        for (int i = from; i <= pool.size() - left; i++) {
            chosen.add(pool.get(i));
            List<Ours> found = search(pool, i + 1, left - 1, sum + pool.get(i).amount(), target, tolerance, chosen);
            chosen.remove(chosen.size() - 1);
            if (found != null) {
                return found;
            }
        }
        return null;
    }
}
