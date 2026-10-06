package com.academy.paybridge.transfer.domain;

import com.academy.paybridge.shared.audit.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * A to-do note: "this transfer still has to be sent to the provider".
 * It is written in the same database transaction as the transfer itself, then a separate worker does the sending.
 */
@Entity
@Table(name = "transfer_outbox")
public class TransferOutbox extends AuditableEntity {

    public enum Status {
        PENDING,
        DONE
    }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, updatable = false)
    private UUID transferId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    @Column(nullable = false)
    private int attempts;

    @Column(nullable = false)
    private Instant nextAttemptAt;

    @Column(length = 500)
    private String lastError;

    protected TransferOutbox() {
    }

    public static TransferOutbox forTransfer(UUID transferId, Instant now) {
        TransferOutbox outbox = new TransferOutbox();
        outbox.transferId = transferId;
        outbox.status = Status.PENDING;
        outbox.attempts = 0;
        outbox.nextAttemptAt = now;
        return outbox;
    }

    public void markDone() {
        this.status = Status.DONE;
    }

    /** The provider could not be reached: try again later. */
    public void scheduleRetry(Instant nextAttemptAt, String error) {
        this.attempts++;
        this.nextAttemptAt = nextAttemptAt;
        this.lastError = truncate(error);
    }

    /** Out of retries. The reconciler will ask the provider what happened. */
    public void giveUp(String error) {
        this.attempts++;
        this.status = Status.DONE;
        this.lastError = truncate("Gave up sending after " + attempts + " attempts: " + error);
    }

    private static String truncate(String text) {
        if (text == null) {
            return null;
        }
        return text.length() <= 500 ? text : text.substring(0, 500);
    }

    public UUID getId() {
        return id;
    }

    public UUID getTransferId() {
        return transferId;
    }

    public Status getStatus() {
        return status;
    }

    public int getAttempts() {
        return attempts;
    }

    public Instant getNextAttemptAt() {
        return nextAttemptAt;
    }

    public String getLastError() {
        return lastError;
    }
}
