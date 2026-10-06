package com.academy.paybridge.transfer.client;

/** The answer to a "name enquiry": whose account is this? */
public record ResolvedAccount(String bankCode, String accountNumber, String accountName) {
}
