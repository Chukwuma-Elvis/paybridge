package com.academy.paybridge.fx.api;

import java.util.Currency;

/**
 * A route money can be converted along. Each corridor sells one currency to buy another.
 * <p>
 * Only one exists today. Adding another is a one-line change here plus a rate source for it.
 */
public enum Corridor {
    /** The customer sells US dollars and receives Nigerian naira. */
    USD_NGN("USD", "NGN");

    private final Currency sell;
    private final Currency buy;

    Corridor(String sellCurrency, String buyCurrency) {
        this.sell = Currency.getInstance(sellCurrency);
        this.buy = Currency.getInstance(buyCurrency);
    }

    /** The currency the customer hands over. */
    public Currency sell() {
        return sell;
    }

    /** The currency the customer receives. */
    public Currency buy() {
        return buy;
    }
}
