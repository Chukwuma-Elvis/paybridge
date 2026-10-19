package com.academy.paybridge.transfer.client;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Settings for talking to Paystack, from {@code paybridge.paystack}. Only used when {@code paybridge.gateway=paystack}.
 * <p>
 * The secret key comes from the environment ({@code PAYSTACK_SECRET_KEY}), never from a file. Use the TEST key ({@code sk_test_...})
 * until you have deliberately decided to move real money. The public key ({@code pk_test_...}) is for card forms in a browser and
 * is not used by PayBridge at all.
 *
 * @param secretKey      the Paystack secret key
 * @param baseUrl        Paystack's API address (the same for test and live: the KEY decides which mode you are in)
 * @param connectTimeout how long to wait to connect before treating the outcome as unknown
 * @param readTimeout    how long to wait for an answer before treating the outcome as unknown
 */
@ConfigurationProperties(prefix = "paybridge.paystack")
public record PaystackProperties(
        @DefaultValue("") String secretKey,
        @DefaultValue("https://api.paystack.co") String baseUrl,
        @DefaultValue("3s") Duration connectTimeout,
        @DefaultValue("10s") Duration readTimeout) {
}
