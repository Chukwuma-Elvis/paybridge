package com.academy.paybridge.compliance.api;

import java.time.LocalDate;

/**
 * A person or business to be screened. Only what screening needs: the identity-document number is deliberately not here.
 *
 * @param fullName    as given; compared after cleaning (case, accents, word order)
 * @param country     two-letter country code, or null if unknown; counts as corroboration when it matches
 * @param dateOfBirth or null if unknown; counts as corroboration when it matches
 */
public record Party(String fullName, String country, LocalDate dateOfBirth) {
}
