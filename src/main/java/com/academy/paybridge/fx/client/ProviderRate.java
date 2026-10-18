package com.academy.paybridge.fx.client;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * What a rate source says.
 *
 * @param midRate the mid-market rate as an exact decimal
 * @param source  a short name for the source, kept with the stored rate
 * @param asOf    the moment the rate was true, which is what decides whether it has gone stale
 */
public record ProviderRate(BigDecimal midRate, String source, Instant asOf) {
}
