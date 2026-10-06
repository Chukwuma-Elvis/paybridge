package com.academy.paybridge.account.domain;

/**
 * Nigeria's NUBAN account number standard: 10 digits, made of a 9-digit serial number followed by a check digit.
 * The check digit is calculated from the 3-digit bank code plus the 9-digit serial, so a mistyped number
 * (or a number from a different bank) almost always fails validation.
 */
public final class Nuban {

    private static final int[] WEIGHTS = {3, 7, 3, 3, 7, 3, 3, 7, 3, 3, 7, 3};

    private Nuban() {
    }

    /** Builds the full 10-digit account number from a 9-digit serial. */
    public static String generate(String bankCode, long serial) {
        String serialDigits = String.format("%09d", serial);
        if (serialDigits.length() != 9) {
            throw new IllegalArgumentException("serial must fit in 9 digits: " + serial);
        }
        return serialDigits + checkDigit(bankCode, serialDigits);
    }

    /** Calculates the check digit for a bank code and a 9-digit serial. */
    public static int checkDigit(String bankCode, String serialDigits) {
        requireDigits(bankCode, 3, "bank code");
        requireDigits(serialDigits, 9, "serial");

        String all = bankCode + serialDigits;
        int sum = 0;
        for (int i = 0; i < WEIGHTS.length; i++) {
            sum += (all.charAt(i) - '0') * WEIGHTS[i];
        }
        int check = 10 - (sum % 10);
        return check == 10 ? 0 : check;
    }

    /** True if {@code accountNumber} is a well-formed NUBAN for this bank code. */
    public static boolean isValid(String bankCode, String accountNumber) {
        if (accountNumber == null || !accountNumber.matches("\\d{10}")) {
            return false;
        }
        String serial = accountNumber.substring(0, 9);
        int given = accountNumber.charAt(9) - '0';
        return checkDigit(bankCode, serial) == given;
    }

    private static void requireDigits(String value, int length, String name) {
        if (value == null || !value.matches("\\d{" + length + "}")) {
            throw new IllegalArgumentException(name + " must be exactly " + length + " digits: " + value);
        }
    }
}
