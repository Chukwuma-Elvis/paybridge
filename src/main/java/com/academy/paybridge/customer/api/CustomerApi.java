package com.academy.paybridge.customer.api;

import java.util.UUID;

/**
 * The front desk of the customer module. Other modules talk to customers only through this.
 */
public interface CustomerApi {

    /**
     * @throws com.academy.paybridge.shared.exception.ResourceNotFoundException if there is no such customer
     */
    CustomerSummary getCustomer(UUID customerId);
}
