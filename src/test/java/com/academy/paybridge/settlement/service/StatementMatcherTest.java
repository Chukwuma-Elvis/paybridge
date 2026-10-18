package com.academy.paybridge.settlement.service;

import com.academy.paybridge.settlement.service.StatementMatcher.Ours;
import com.academy.paybridge.settlement.service.StatementMatcher.Result;
import com.academy.paybridge.settlement.service.StatementMatcher.Theirs;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** The three matching passes, the tolerance boundaries, and the promise that every line ends up somewhere. */
class StatementMatcherTest {

    private static final LocalDate DAY = LocalDate.of(2026, 10, 14);

    private static Ours ours(String ref, long amount) {
        return new Ours(UUID.randomUUID(), ref, amount, DAY);
    }

    private static Ours ours(String ref, long amount, LocalDate date) {
        return new Ours(UUID.randomUUID(), ref, amount, date);
    }

    private static Theirs theirs(int row, String ref, long amount) {
        return new Theirs(UUID.randomUUID(), row, ref, amount, DAY);
    }

    private static Theirs theirs(int row, String ref, long amount, LocalDate date) {
        return new Theirs(UUID.randomUUID(), row, ref, amount, date);
    }

    private static Result match(List<Ours> o, List<Theirs> t) {
        return StatementMatcher.match(o, t, 1, 1);
    }

    private static void everyLineIsAccountedFor(List<Ours> o, List<Theirs> t, Result r) {
        int oursSeen = r.unmatchedOurs().size() + r.differences().size()
                + r.matched().values().stream().mapToInt(List::size).sum();
        int theirsSeen = r.matched().size() + r.differences().size() + r.unmatchedTheirs().size();
        assertThat(oursSeen).as("our lines accounted for").isEqualTo(o.size());
        assertThat(theirsSeen).as("their lines accounted for").isEqualTo(t.size());
    }

    @Test
    void identicalRecordsMatchCompletely() {
        List<Ours> o = List.of(ours("RM-1", 100_000), ours("RM-2", 50_000));
        List<Theirs> t = List.of(theirs(2, "RM-1", 100_000), theirs(3, "RM-2", 50_000));

        Result r = match(o, t);

        assertThat(r.matched()).hasSize(2);
        assertThat(r.differences()).isEmpty();
        assertThat(r.unmatchedOurs()).isEmpty();
        assertThat(r.unmatchedTheirs()).isEmpty();
        everyLineIsAccountedFor(o, t, r);
    }

    @Test
    void aLineOnlyWeHaveIsUnmatchedOurs() {
        List<Ours> o = List.of(ours("RM-1", 100_000), ours("RM-2", 50_000));
        List<Theirs> t = List.of(theirs(2, "RM-1", 100_000));

        Result r = match(o, t);

        assertThat(r.unmatchedOurs()).containsExactly(o.get(1).id());
        everyLineIsAccountedFor(o, t, r);
    }

    @Test
    void aLineOnlyTheyHaveIsUnmatchedTheirsWithAReason() {
        List<Ours> o = List.of(ours("RM-1", 100_000));
        List<Theirs> t = List.of(theirs(2, "RM-1", 100_000), theirs(3, "RM-9", 70_000));

        Result r = match(o, t);

        assertThat(r.unmatchedTheirs()).containsOnlyKeys(t.get(1).id());
        assertThat(r.unmatchedTheirs().get(t.get(1).id())).contains("not in our records");
        everyLineIsAccountedFor(o, t, r);
    }

    @Test
    void sameReferenceDifferentAmountIsADifferenceNotTwoUnmatchedLines() {
        List<Ours> o = List.of(ours("RM-1", 100_000));
        List<Theirs> t = List.of(theirs(2, "RM-1", 99_000));

        Result r = match(o, t);

        assertThat(r.differences()).singleElement().satisfies(d -> {
            assertThat(d.theirs()).isEqualTo(t.get(0).id());
            assertThat(d.ours()).isEqualTo(o.get(0).id());
        });
        assertThat(r.matched()).isEmpty();
        everyLineIsAccountedFor(o, t, r);
    }

    @Test
    void aDuplicatedStatementLineIsFlaggedAsADuplicate() {
        List<Ours> o = List.of(ours("RM-1", 100_000));
        List<Theirs> t = List.of(theirs(2, "RM-1", 100_000), theirs(3, "RM-1", 100_000));

        Result r = match(o, t);

        assertThat(r.matched()).hasSize(1);
        assertThat(r.unmatchedTheirs()).hasSize(1);
        assertThat(r.unmatchedTheirs().values()).allSatisfy(reason -> assertThat(reason).contains("Duplicate"));
        everyLineIsAccountedFor(o, t, r);
    }

    @Test
    void aFundingAndItsRefundShareAReferenceButStayApart() {
        // the money in (+) and the same money back (-) carry the same reference; the amounts keep them apart
        List<Ours> o = List.of(ours("RM-1", 100_000), ours("RM-1", -100_000));
        List<Theirs> t = List.of(theirs(2, "RM-1", -100_000), theirs(3, "RM-1", 100_000));

        Result r = match(o, t);

        assertThat(r.matched()).hasSize(2);
        assertThat(r.differences()).isEmpty();
        everyLineIsAccountedFor(o, t, r);
    }

    // ---- tolerance

    @Test
    void anAmountJustInsideTheToleranceMatchesAndJustOutsideDoesNot() {
        assertThat(match(List.of(ours("RM-1", 100_000)), List.of(theirs(2, "RM-1", 100_001))).matched()).hasSize(1);
        Result outside = match(List.of(ours("RM-1", 100_000)), List.of(theirs(2, "RM-1", 100_002)));
        assertThat(outside.matched()).isEmpty();
        assertThat(outside.differences()).hasSize(1);
    }

    @Test
    void theToleranceIsConfigurable() {
        Result strict = StatementMatcher.match(List.of(ours("RM-1", 100_000)), List.of(theirs(2, "RM-1", 100_001)), 0, 1);
        Result loose = StatementMatcher.match(List.of(ours("RM-1", 100_000)), List.of(theirs(2, "RM-1", 100_050)), 50, 1);

        assertThat(strict.differences()).hasSize(1);
        assertThat(loose.matched()).hasSize(1);
    }

    // ---- lines without a reference

    @Test
    void aLineWithoutAReferenceMatchesByAmountAndDate() {
        List<Ours> o = List.of(ours("RM-1", 100_000, DAY));
        List<Theirs> t = List.of(theirs(2, null, 100_000, DAY.plusDays(1)));

        assertThat(match(o, t).matched()).hasSize(1);
    }

    @Test
    void theDateToleranceHasAHardEdge() {
        List<Ours> o = List.of(ours("RM-1", 100_000, DAY));

        assertThat(match(o, List.of(theirs(2, null, 100_000, DAY.plusDays(1)))).matched()).hasSize(1);
        Result outside = match(o, List.of(theirs(2, null, 100_000, DAY.plusDays(2))));
        assertThat(outside.matched()).isEmpty();
        assertThat(outside.unmatchedOurs()).hasSize(1);
        assertThat(outside.unmatchedTheirs()).hasSize(1);
    }

    @Test
    void theNearestDateWinsWhenSeveralLinesHaveTheSameAmount() {
        Ours near = ours("RM-1", 100_000, DAY);
        Ours far = ours("RM-2", 100_000, DAY.minusDays(1));
        Theirs t = theirs(2, null, 100_000, DAY);

        Result r = match(List.of(far, near), List.of(t));

        assertThat(r.matched().get(t.id())).containsExactly(near.id());
        assertThat(r.unmatchedOurs()).containsExactly(far.id());
    }

    @Test
    void oneStatementLineCanCoverSeveralOfOurs() {
        List<Ours> o = List.of(ours("RM-1", 30_000), ours("RM-2", 20_000), ours("RM-3", 50_000), ours("RM-4", 999));
        Theirs total = theirs(2, null, 100_000);

        Result r = match(o, List.of(total));

        assertThat(r.matched().get(total.id())).hasSize(3);
        assertThat(r.unmatchedOurs()).containsExactly(o.get(3).id());
        everyLineIsAccountedFor(o, List.of(total), r);
    }

    @Test
    void aGroupThatDoesNotAddUpIsNotForced() {
        List<Ours> o = List.of(ours("RM-1", 30_000), ours("RM-2", 20_000));
        Theirs total = theirs(2, null, 51_000);

        Result r = match(o, List.of(total));

        assertThat(r.matched()).isEmpty();
        assertThat(r.unmatchedTheirs()).hasSize(1);
        assertThat(r.unmatchedOurs()).hasSize(2);
    }

    @Test
    void emptyInputsGiveEmptyResults() {
        Result r = match(List.of(), List.of());

        assertThat(r.matched()).isEmpty();
        assertThat(r.differences()).isEmpty();
        assertThat(r.unmatchedOurs()).isEmpty();
        assertThat(r.unmatchedTheirs()).isEmpty();
    }

    @Test
    void aLineIsNeverUsedTwice() {
        List<Ours> o = List.of(ours("RM-1", 100_000));
        List<Theirs> t = List.of(theirs(2, "RM-1", 100_000), theirs(3, null, 100_000));

        Result r = match(o, t);

        assertThat(r.matched().values().stream().mapToInt(List::size).sum()).isEqualTo(1);
        assertThat(r.unmatchedTheirs()).hasSize(1);
        everyLineIsAccountedFor(o, t, r);
    }
}
