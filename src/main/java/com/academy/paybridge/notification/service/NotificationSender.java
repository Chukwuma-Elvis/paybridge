package com.academy.paybridge.notification.service;

import com.academy.paybridge.notification.domain.Notification;

/**
 * How an alert actually reaches a person. Today it only writes to the log; a real SMS or email provider
 * is plugged in by adding another implementation, without touching anything else.
 */
public interface NotificationSender {

    void send(Notification.Channel channel, String recipient, String message);
}
