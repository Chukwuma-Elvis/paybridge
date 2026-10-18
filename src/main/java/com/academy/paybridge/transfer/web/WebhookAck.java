package com.academy.paybridge.transfer.web;

/** The reply to a webhook. Providers only look at the status code; the result is there to help whoever is debugging. */
public record WebhookAck(String result) {
}
