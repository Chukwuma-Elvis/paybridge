package com.academy.paybridge.remittance.client;

/** The corridor partner could not be reached or gave no usable answer. The outcome is UNKNOWN: never treat it as "not funded". */
public class PartnerUnavailableException extends RuntimeException {

    public PartnerUnavailableException(String message) {
        super(message);
    }
}
