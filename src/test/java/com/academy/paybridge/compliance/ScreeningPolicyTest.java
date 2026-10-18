package com.academy.paybridge.compliance;

import com.academy.paybridge.compliance.api.Party;
import com.academy.paybridge.compliance.api.ScreeningOutcome;
import com.academy.paybridge.compliance.service.AmlProperties;
import com.academy.paybridge.compliance.service.FakeScreeningProvider;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The screening rule, with no database. The rule and the name comparison are package-private on purpose, so they are reached
 * through reflection here rather than widening their visibility just for a test.
 */
class ScreeningPolicyTest {

    private static final AmlProperties.Screening CONFIG = new AmlProperties.Screening(0.85, 0.95, 0.70);
    private static final FakeScreeningProvider LIST = new FakeScreeningProvider();

    private static ScreeningOutcome screen(Party party) throws Exception {
        Class<?> policy = Class.forName("com.academy.paybridge.compliance.service.ScreeningPolicy");
        Method assess = policy.getDeclaredMethod("assess", Party.class, List.class, AmlProperties.Screening.class);
        assess.setAccessible(true);
        Object assessment = assess.invoke(null, party, LIST.candidatesFor(party), CONFIG);
        Method outcome = assessment.getClass().getDeclaredMethod("outcome");
        outcome.setAccessible(true);
        return (ScreeningOutcome) outcome.invoke(assessment);
    }

    private static double similarity(String a, String b) throws Exception {
        Class<?> cls = Class.forName("com.academy.paybridge.compliance.service.NameSimilarity");
        Method between = cls.getDeclaredMethod("between", String.class, String.class);
        between.setAccessible(true);
        return (double) between.invoke(null, a, b);
    }

    // ---- names

    @Test
    void wordOrderCaseAccentsAndPunctuationDoNotMatter() throws Exception {
        assertThat(similarity("Zorgon Testlist", "testlist, ZORGON")).isEqualTo(1.0);
        assertThat(similarity("José Ñandú", "Jose Nandu")).isEqualTo(1.0);
    }

    @Test
    void unlikeNamesScoreLow() throws Exception {
        assertThat(similarity("Chidi Okafor", "Zorgon Testlist")).isLessThan(0.4);
        assertThat(similarity("", "Zorgon Testlist")).isZero();
        assertThat(similarity(null, "Zorgon Testlist")).isZero();
    }

    @Test
    void oneLetterOffIsStillVeryClose() throws Exception {
        assertThat(similarity("Zorgon Testlist", "Zorgan Testlist")).isGreaterThan(0.9);
    }

    // ---- the tiers

    @Test
    void anOrdinaryCustomerIsClear() throws Exception {
        assertThat(screen(new Party("Chidi Okafor", "NG", LocalDate.of(1990, 3, 3)))).isEqualTo(ScreeningOutcome.CLEAR);
    }

    @Test
    void anExactNameWithMatchingCountryAndBirthDateIsBlocked() throws Exception {
        assertThat(screen(new Party("Zorgon Testlist", "ZZ", LocalDate.of(1970, 1, 1)))).isEqualTo(ScreeningOutcome.BLOCK);
    }

    @Test
    void anExactNameWithOneCorroboratorIsBlocked() throws Exception {
        assertThat(screen(new Party("Alina Samplova", "RU", null))).isEqualTo(ScreeningOutcome.BLOCK);
    }

    @Test
    void anExactNameWithNothingToBackItUpIsReviewedNeverCleared() throws Exception {
        // the same name, but a different country and no birth date: ambiguous, so a person decides
        assertThat(screen(new Party("Alina Samplova", "NG", null))).isEqualTo(ScreeningOutcome.REVIEW);
    }

    @Test
    void aNearMatchIsReviewed() throws Exception {
        assertThat(screen(new Party("Kwame Mockersen", "NG", null))).isEqualTo(ScreeningOutcome.REVIEW);
    }

    @Test
    void aWeakNameMatchWithNothingElseIsAFalsePositiveAndClear() throws Exception {
        assertThat(screen(new Party("Alinna Sampson", "NG", LocalDate.of(2000, 1, 1)))).isEqualTo(ScreeningOutcome.CLEAR);
    }

    @Test
    void aWeakNameMatchIsReviewedWhenBothCountryAndBirthDateAgree() throws Exception {
        // "Alina Sampson" is roughly 0.8 similar to "Alina Samplova", under the review line, but country AND birth date match
        assertThat(screen(new Party("Alina Sampson", "RU", LocalDate.of(1982, 6, 15)))).isEqualTo(ScreeningOutcome.REVIEW);
    }

    @Test
    void aliasesAreComparedToo() throws Exception {
        assertThat(screen(new Party("Z. Testlist", "ZZ", null))).isEqualTo(ScreeningOutcome.BLOCK);
    }

    @Test
    void thresholdsComeFromConfigurationNotFromCode() throws Exception {
        Class<?> policy = Class.forName("com.academy.paybridge.compliance.service.ScreeningPolicy");
        Method outcomeFor = policy.getDeclaredMethod("outcomeFor", double.class, int.class, AmlProperties.Screening.class);
        outcomeFor.setAccessible(true);
        AmlProperties.Screening strict = new AmlProperties.Screening(0.50, 0.60, 0.40);

        assertThat(outcomeFor.invoke(null, 0.55, 0, CONFIG)).isEqualTo(ScreeningOutcome.CLEAR);
        assertThat(outcomeFor.invoke(null, 0.55, 0, strict)).isEqualTo(ScreeningOutcome.REVIEW);
        assertThat(outcomeFor.invoke(null, 0.65, 1, strict)).isEqualTo(ScreeningOutcome.BLOCK);
    }
}
