package com.academy.paybridge.shared.exception;

import java.util.Currency;

public class CurrencyMismatchException extends PayBridgeException {

    public CurrencyMismatchException(Currency expected, Currency actual) {
        super(ErrorCode.CURRENCY_MISMATCH, "Currency mismatch: " + expected + " vs " + actual);
    }

}
