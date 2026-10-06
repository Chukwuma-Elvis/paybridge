package com.academy.paybridge.notification.domain;

import com.academy.paybridge.shared.audit.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.UUID;

@Entity
@Table(name = "notification")
public class Notification extends AuditableEntity {

    public enum Channel {
        SMS,
        EMAIL
    }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, updatable = false)
    private UUID customerId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private Channel channel;

    @Column(nullable = false, updatable = false)
    private String recipient;

    @Column(nullable = false, updatable = false)
    private String eventType;

    @Column(nullable = false, updatable = false)
    private String reference;

    @Column(nullable = false, updatable = false, length = 500)
    private String message;

    protected Notification() {
    }

    public Notification(UUID customerId, Channel channel, String recipient,
                        String eventType, String reference, String message) {
        this.customerId = customerId;
        this.channel = channel;
        this.recipient = recipient;
        this.eventType = eventType;
        this.reference = reference;
        this.message = message.length() <= 500 ? message : message.substring(0, 500);
    }

    public UUID getId() {
        return id;
    }

    public UUID getCustomerId() {
        return customerId;
    }

    public Channel getChannel() {
        return channel;
    }

    public String getRecipient() {
        return recipient;
    }

    public String getEventType() {
        return eventType;
    }

    public String getReference() {
        return reference;
    }

    public String getMessage() {
        return message;
    }
}
