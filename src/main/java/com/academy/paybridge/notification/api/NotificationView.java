package com.academy.paybridge.notification.api;

import java.time.Instant;
import java.util.UUID;

/** What the outside world sees of a notification. */
public record NotificationView(
        UUID id,
        UUID customerId,
        String channel,
        String recipient,
        String eventType,
        String reference,
        String message,
        Instant createdAt) {
}
