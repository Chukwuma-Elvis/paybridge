package com.academy.paybridge.settlement.service;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/** The statement reader: forgiving about formatting, strict about meaning, and all-or-nothing. */
class StatementParserTest {

    private static StatementParser.Result parse(String text) {
        return StatementParser.parse(text.getBytes(StandardCharsets.UTF_8), 100, "USD");
    }

    @Test
    void aGoodStatementIsReadRowByRow() {
        StatementParser.Result result = parse("reference,amount,currency,date\nRM-1,100000,USD,2026-10-14\n,-5000,USD,2026-10-15\n");

        assertThat(result.isValid()).isTrue();
        assertThat(result.rows()).hasSize(2);
        assertThat(result.rows().get(0)).isEqualTo(new StatementParser.Row(2, "RM-1", 100_000, "USD", LocalDate.of(2026, 10, 14)));
        assertThat(result.rows().get(1).reference()).as("a missing reference is allowed").isNull();
        assertThat(result.rows().get(1).amountMinor()).isEqualTo(-5_000);
    }

    @Test
    void harmlessFormattingIsForgiven() {
        String messy = "﻿\r\n  Reference , AMOUNT, Currency ,Date\r\n\r\n RM-1 , +100000 , usd , 2026-10-14 \r\n\r\n";

        StatementParser.Result result = parse(messy);

        assertThat(result.errors()).isEmpty();
        assertThat(result.rows()).singleElement().satisfies(row -> {
            assertThat(row.reference()).isEqualTo("RM-1");
            assertThat(row.amountMinor()).isEqualTo(100_000);
            assertThat(row.currency()).isEqualTo("USD");
        });
    }

    @Test
    void aBadRowIsReportedWithItsRowNumberAndNothingIsImported() {
        StatementParser.Result result = parse("""
                reference,amount,currency,date
                RM-1,100000,USD,2026-10-14
                RM-2,12.50,USD,2026-10-14
                RM-3,100000,EUR,2026-10-14
                RM-4,100000,USD,14/10/2026
                RM-5,0,USD,2026-10-14
                RM-6,1,USD
                """);

        assertThat(result.isValid()).isFalse();
        assertThat(result.errors()).anyMatch(e -> e.startsWith("Row 3:") && e.contains("whole number"));
        assertThat(result.errors()).anyMatch(e -> e.startsWith("Row 4:") && e.contains("currency"));
        assertThat(result.errors()).anyMatch(e -> e.startsWith("Row 5:") && e.contains("yyyy-MM-dd"));
        assertThat(result.errors()).anyMatch(e -> e.startsWith("Row 6:") && e.contains("zero"));
        assertThat(result.errors()).anyMatch(e -> e.startsWith("Row 7:") && e.contains("4 values"));
    }

    @Test
    void theHeaderMustBeExactlyWhatWeExpect() {
        assertThat(parse("ref,amount,currency,date\nRM-1,1,USD,2026-10-14").errors()).singleElement().asString().contains("header");
        assertThat(parse("").errors()).singleElement().asString().contains("empty");
        assertThat(parse("reference,amount,currency,date\n\n\n").errors()).singleElement().asString().contains("no rows");
    }

    @Test
    void referencesWithOddCharactersAreRefused() {
        assertThat(parse("reference,amount,currency,date\nRM 1;DROP,1,USD,2026-10-14").errors()).isNotEmpty();
        assertThat(parse("reference,amount,currency,date\n" + "R".repeat(101) + ",1,USD,2026-10-14").errors()).isNotEmpty();
    }

    @Test
    void anAmountThatDoesNotFitInALongIsRefusedNotWrapped() {
        assertThat(parse("reference,amount,currency,date\nRM-1,9999999999999999999,USD,2026-10-14").errors()).isNotEmpty();
    }

    @Test
    void tooManyRowsAreRefused() {
        StringBuilder big = new StringBuilder("reference,amount,currency,date\n");
        for (int i = 0; i < 6; i++) {
            big.append("RM-").append(i).append(",1,USD,2026-10-14\n");
        }

        StatementParser.Result result = StatementParser.parse(big.toString().getBytes(StandardCharsets.UTF_8), 5, "USD");

        assertThat(result.errors()).anyMatch(e -> e.contains("more than 5 rows"));
    }

    @Test
    void theNumberOfReportedErrorsIsBounded() {
        StringBuilder bad = new StringBuilder("reference,amount,currency,date\n");
        for (int i = 0; i < 100; i++) {
            bad.append("RM-").append(i).append(",x,USD,2026-10-14\n");
        }

        assertThat(parse(bad.toString()).errors().size()).isLessThanOrEqualTo(22);
    }
}
