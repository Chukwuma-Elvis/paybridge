package com.academy.paybridge.fx.client;

import com.academy.paybridge.fx.api.Corridor;

/**
 * The seam between PayBridge and a source of exchange rates, the same idea as {@code TransferGateway} is for banks.
 * The rest of the {@code fx} module only sees this interface, so a real source is one new class.
 */
public interface RateProvider {

    /**
     * The current mid-market rate for the corridor.
     *
     * @throws RateProviderUnavailableException if no clear answer can be had (outage, timeout). Never guess a rate.
     */
    ProviderRate fetch(Corridor corridor);
}
