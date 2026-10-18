package com.academy.paybridge.remittance.api;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Everything needed to start a remittance. The amounts are NOT here: they come from the quote, so a caller cannot ask for one amount
 * and a different quote.
 *
 * @param partnerCode      the corridor partner that collected the sender's money
 * @param partnerReference the partner's own reference for this payment (accepted once per partner)
 * @param quoteId          the quote that fixes the rate, the fee and the naira the recipient gets
 */
public record CreateRemittance(String partnerCode, String partnerReference, UUID quoteId, Sender sender, Recipient recipient) {

    /**
     * @param reference the partner's id for the sender, used to count that person's limits; stable across payments
     * @param country   two-letter code, optional
     */
    public record Sender(String reference, String fullName, String country, LocalDate dateOfBirth) {
    }

    /**
     * @param bankCode      required for {@link RecipientType#BANK}, ignored for PAYBRIDGE
     * @param accountNumber ten digits
     */
    public record Recipient(RecipientType type, String fullName, String country, String bankCode, String accountNumber) {
    }
}
