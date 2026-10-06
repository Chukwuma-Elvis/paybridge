package com.academy.paybridge.notification.service;

import com.academy.paybridge.notification.domain.Notification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
class LoggingNotificationSender implements NotificationSender {

    private static final Logger log = LoggerFactory.getLogger(LoggingNotificationSender.class);

    @Override
    public void send(Notification.Channel channel, String recipient, String message) {
        log.info("[{}] to {}: {}", channel, recipient, message);
    }
}
