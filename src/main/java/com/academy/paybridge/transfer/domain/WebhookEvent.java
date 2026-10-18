package com.academy.paybridge.transfer.domain;

import com.academy.paybridge.shared.audit.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.UUID;

/**
 * The record of one webhook delivery. Rows are inserted by a native {@code INSERT ... ON CONFLICT DO NOTHING}
 * (see the repository), which is what makes "have I seen this event before?" safe when the same event arrives twice
 * at the same instant. After that the row is only updated, as the event is worked through.
 */
@Entity
@Table(name = "webhook_event")
public class WebhookEvent extends AuditableEntity {

    private static final int MAX_DETAIL_LENGTH = 500;

    @Id
    private UUID id;

    @Column(nullable = false, updatable = false)
    private String provider;

    @Column(name = "event_id", nullable = false, updatable = false)
    private String eventId;

    @Column(name = "event_type", nullable = false, updatable = false)
    private String eventType;

    @Column(updatable = false)
    private String reference;

    @Column(updatable = false)
    private String outcome;

    @Column(name = "amount_minor", updatable = false)
    private Long amountMinor;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private WebhookStatus status;

    @Column
    private String detail;

    @Column(name = "payload_sha256", nullable = false, updatable = false)
    private String payloadSha256;

    @Column(nullable = false)
    private int attempts;

    protected WebhookEvent() {
    }

    /** Records where the event ended up. The detail (kept short) says why, for the person who has to look. */
    public void finish(WebhookStatus newStatus, String why) {
        this.status = newStatus;
        this.detail = why == null ? null : truncate(why);
    }

    private static String truncate(String text) {
        return text.length() <= MAX_DETAIL_LENGTH ? text : text.substring(0, MAX_DETAIL_LENGTH);
    }

    public UUID getId() {
        return id;
    }

    public String getProvider() {
        return provider;
    }

    public String getEventId() {
        return eventId;
    }

    public String getEventType() {
        return eventType;
    }

    public String getReference() {
        return reference;
    }

    public String getOutcome() {
        return outcome;
    }

    public Long getAmountMinor() {
        return amountMinor;
    }

    public WebhookStatus getStatus() {
        return status;
    }

    public String getDetail() {
        return detail;
    }

    public String getPayloadSha256() {
        return payloadSha256;
    }

    public int getAttempts() {
        return attempts;
    }
}
