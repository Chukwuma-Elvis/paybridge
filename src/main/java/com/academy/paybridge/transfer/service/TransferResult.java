package com.academy.paybridge.transfer.service;

import com.academy.paybridge.transfer.api.TransferSummary;

/** The transfer, and whether this answer is a replay of an earlier request with the same Idempotency-Key. */
public record TransferResult(TransferSummary transfer, boolean replayed) {
}
