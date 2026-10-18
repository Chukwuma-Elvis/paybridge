package com.academy.paybridge.transfer.client;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.Map;

/**
 * Webhook settings, from {@code paybridge.webhooks} in application.yml.
 * <p>
 * The secrets are what make a webhook believable, so they come from the environment, never from a file.
 * A provider whose secret is empty is switched off.
 *
 * @param maxBodyBytes the largest webhook body we will read (larger is refused with 413)
 * @param paystack     Paystack settings
 * @param monnify      Monnify settings
 */
@ConfigurationProperties(prefix = "paybridge.webhooks")
public record WebhookProperties(
        @DefaultValue("65536") int maxBodyBytes,
        @DefaultValue Paystack paystack,
        @DefaultValue Monnify monnify,
        Map<String, Partner> partners) {

    @ConstructorBinding
    public WebhookProperties {
        partners = partners == null ? Map.of() : Map.copyOf(partners);
    }

    /** Settings without any corridor partners. */
    public WebhookProperties(int maxBodyBytes, Paystack paystack, Monnify monnify) {
        this(maxBodyBytes, paystack, monnify, Map.of());
    }

    /** A corridor partner signs its funding events with this shared secret (HMAC-SHA512 of the raw body, hex). Empty means off. */
    public record Partner(@DefaultValue("") String secretKey) {
    }

    /** Paystack signs with your secret key (the same {@code sk_...} key used for its API). */
    public record Paystack(@DefaultValue("") String secretKey) {
    }

    /**
     * @param secretKey       your Monnify client secret key
     * @param signatureScheme how Monnify's signature is computed. Its documentation describes two recipes; HMAC-SHA512 is the
     *                        one its sample code uses and is the default. If real deliveries are refused with a bad signature,
     *                        try {@code sha512-secret-prefix}
     */
    public record Monnify(@DefaultValue("") String secretKey,
                          @DefaultValue("hmac-sha512") MonnifySignatureScheme signatureScheme) {
    }
}
