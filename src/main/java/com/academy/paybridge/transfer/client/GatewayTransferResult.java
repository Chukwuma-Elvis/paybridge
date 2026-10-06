package com.academy.paybridge.transfer.client;

public record GatewayTransferResult(GatewayOutcome outcome, String providerReference, String message) {

    public static GatewayTransferResult of(GatewayOutcome outcome, String providerReference, String message) {
        return new GatewayTransferResult(outcome, providerReference, message);
    }
}
