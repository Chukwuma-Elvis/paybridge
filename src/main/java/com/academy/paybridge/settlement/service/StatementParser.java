package com.academy.paybridge.settlement.service;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Reads a partner's statement: a CSV with the header {@code reference,amount,currency,date}.
 * <ul>
 *   <li>{@code reference}: our reference for the movement (may be empty if the partner does not know it)</li>
 *   <li>{@code amount}: a whole number of minor units (US cents), signed; positive means money the partner holds for us</li>
 *   <li>{@code currency}: three letters, and it must be the batch's currency</li>
 *   <li>{@code date}: {@code yyyy-MM-dd}</li>
 * </ul>
 * <b>Tolerant</b> about harmless formatting (a byte-order mark, Windows line endings, blank lines, spaces around values, a header in any
 * letter case) and <b>strict</b> about meaning: a bad row is reported with its row number and is never guessed at, and a statement with
 * any bad row is rejected as a whole so that nothing is ever half-imported.
 */
final class StatementParser {

    static final String HEADER = "reference,amount,currency,date";
    private static final Pattern REFERENCE = Pattern.compile("[A-Za-z0-9:._/-]{0,100}");
    private static final Pattern AMOUNT = Pattern.compile("[+-]?\\d{1,18}");
    private static final int MAX_REPORTED_ERRORS = 20;

    private StatementParser() {
    }

    /** One good row. {@code rowNumber} counts the header as row 1. */
    record Row(int rowNumber, String reference, long amountMinor, String currency, LocalDate valueDate) {
    }

    record Result(List<Row> rows, List<String> errors) {
        boolean isValid() {
            return errors.isEmpty();
        }
    }

    static Result parse(byte[] content, int maxRows, String expectedCurrency) {
        List<String> errors = new ArrayList<>();
        List<Row> rows = new ArrayList<>();
        String text = new String(content, StandardCharsets.UTF_8);
        if (text.startsWith("﻿")) {
            text = text.substring(1);
        }
        String[] lines = text.split("\\r?\\n", -1);

        int headerLine = -1;
        for (int i = 0; i < lines.length; i++) {
            if (!lines[i].isBlank()) {
                headerLine = i;
                break;
            }
        }
        if (headerLine < 0) {
            return new Result(rows, List.of("The statement is empty"));
        }
        if (!lines[headerLine].replace(" ", "").toLowerCase(Locale.ROOT).equals(HEADER)) {
            return new Result(rows, List.of("Row 1: the header must be exactly '" + HEADER + "'"));
        }

        int dataRows = 0;
        for (int i = headerLine + 1; i < lines.length; i++) {
            String line = lines[i];
            if (line.isBlank()) {
                continue;
            }
            int rowNumber = i - headerLine + 1;
            if (++dataRows > maxRows) {
                errors.add("The statement has more than " + maxRows + " rows");
                break;
            }
            String[] cells = line.split(",", -1);
            if (cells.length != 4) {
                addError(errors, rowNumber, "expected 4 values but found " + cells.length);
                continue;
            }
            String reference = cells[0].trim();
            String amountText = cells[1].trim();
            String currency = cells[2].trim().toUpperCase(Locale.ROOT);
            String dateText = cells[3].trim();

            boolean ok = true;
            if (!REFERENCE.matcher(reference).matches()) {
                addError(errors, rowNumber, "the reference has characters that are not allowed or is too long");
                ok = false;
            }
            long amount = 0;
            if (!AMOUNT.matcher(amountText).matches()) {
                addError(errors, rowNumber, "the amount must be a whole number of minor units (no decimal point)");
                ok = false;
            } else {
                try {
                    amount = Long.parseLong(amountText.startsWith("+") ? amountText.substring(1) : amountText);
                    if (amount == 0) {
                        addError(errors, rowNumber, "the amount cannot be zero");
                        ok = false;
                    }
                } catch (NumberFormatException e) {
                    addError(errors, rowNumber, "the amount is too large");
                    ok = false;
                }
            }
            if (!currency.equals(expectedCurrency)) {
                addError(errors, rowNumber, "the currency must be " + expectedCurrency);
                ok = false;
            }
            LocalDate date = null;
            try {
                date = LocalDate.parse(dateText);
            } catch (DateTimeParseException e) {
                addError(errors, rowNumber, "the date must be yyyy-MM-dd");
                ok = false;
            }
            if (ok) {
                rows.add(new Row(rowNumber, reference.isEmpty() ? null : reference, amount, currency, date));
            }
        }
        if (errors.isEmpty() && rows.isEmpty()) {
            errors.add("The statement has a header but no rows");
        }
        return new Result(rows, errors);
    }

    private static void addError(List<String> errors, int rowNumber, String message) {
        if (errors.size() < MAX_REPORTED_ERRORS) {
            errors.add("Row " + rowNumber + ": " + message);
        } else if (errors.size() == MAX_REPORTED_ERRORS) {
            errors.add("...and more rows have problems");
        }
    }
}
