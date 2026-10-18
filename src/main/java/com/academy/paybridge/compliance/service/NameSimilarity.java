package com.academy.paybridge.compliance.service;

import java.text.Normalizer;
import java.util.Arrays;
import java.util.Locale;

/**
 * How alike two names are, from 0 (nothing in common) to 1 (the same name).
 * <p>
 * Names are cleaned first: accents removed, case ignored, punctuation dropped, and the words sorted so that "Okafor Chidi" and
 * "Chidi Okafor" are the same. The cleaned strings are then compared with edit distance (the fewest single-letter changes that turn
 * one into the other), scaled by length.
 */
final class NameSimilarity {

    private NameSimilarity() {
    }

    static double between(String a, String b) {
        String left = clean(a);
        String right = clean(b);
        if (left.isEmpty() || right.isEmpty()) {
            return 0.0;
        }
        int longest = Math.max(left.length(), right.length());
        return 1.0 - (double) editDistance(left, right) / longest;
    }

    static String clean(String name) {
        if (name == null) {
            return "";
        }
        String plain = Normalizer.normalize(name, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9 ]", " ")
                .trim();
        if (plain.isEmpty()) {
            return "";
        }
        String[] words = plain.split("\\s+");
        Arrays.sort(words);
        return String.join(" ", words);
    }

    private static int editDistance(String a, String b) {
        int[] previous = new int[b.length() + 1];
        int[] current = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            previous[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            current[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int substitution = previous[j - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1);
                current[j] = Math.min(substitution, Math.min(previous[j] + 1, current[j - 1] + 1));
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[b.length()];
    }
}
