package com.academy.paybridge.transfer.client;

/** The two recipes Monnify's documentation gives for its {@code monnify-signature} header. */
public enum MonnifySignatureScheme {
    /** HMAC-SHA512 of the raw body, keyed with the client secret key. What Monnify's sample code does. */
    HMAC_SHA512,
    /** SHA-512 of the client secret key followed by the raw body. What part of Monnify's prose describes. */
    SHA512_SECRET_PREFIX
}
