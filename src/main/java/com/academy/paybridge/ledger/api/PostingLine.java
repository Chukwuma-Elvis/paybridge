package com.academy.paybridge.ledger.api;

import com.academy.paybridge.shared.money.Money;

import java.util.UUID;

/** One side of a journal entry. A negative amount debits the account, a positive amount credits it. */
public record PostingLine(UUID accountId, Money amount) {
}
