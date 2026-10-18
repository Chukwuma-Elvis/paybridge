package com.academy.paybridge.fx.api;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * The rate in force right now for a corridor.
 *
 * @param corridor     the route
 * @param midRate      the mid-market rate: how many units of the bought currency one unit of the sold currency is worth,
 *                     before any margin (an exact decimal, never a floating-point number)
 * @param customerRate the rate the customer actually gets: the mid rate less our spread, rounded down
 * @param spreadBps    our margin in basis points (100 basis points is 1%)
 * @param source       where the rate came from
 * @param asOf         the moment the rate was true
 */
public record CurrentRate(Corridor corridor, BigDecimal midRate, BigDecimal customerRate, int spreadBps,
                          String source, Instant asOf) {
}
