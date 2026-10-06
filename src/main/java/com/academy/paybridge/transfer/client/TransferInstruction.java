package com.academy.paybridge.transfer.client;

/**
 * What we ask the provider to send. {@code reference} is OUR unique reference for the transfer: the provider uses it
 * to recognise a repeated request, which is what makes retrying safe.
 */
public record TransferInstruction(
        String reference,
        long amountMinor,
        String currency,
        String recipientCode,
        String narration) {
}
