package com.academy.paybridge.transfer.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * "This client key already produced that transfer." Rows are inserted by a native
 * {@code INSERT ... ON CONFLICT DO NOTHING} (see the repository) and completed with the transfer id.
 */
@Entity
@Table(name = "idempotency_key")
public class IdempotencyKey {

    @Id
    private UUID id;

    @Column(nullable = false, updatable = false)
    private String scope;

    @Column(name = "idem_key", nullable = false, updatable = false)
    private String idemKey;

    @Column(nullable = false, updatable = false)
    private String requestHash;

    @Column
    private UUID transferId;

    @Column(insertable = false, updatable = false)
    private Instant createdAt;

    protected IdempotencyKey() {
    }

    public void completeWith(UUID transferId) {
        this.transferId = transferId;
    }

    public String getScope() {
        return scope;
    }

    public String getIdemKey() {
        return idemKey;
    }

    public String getRequestHash() {
        return requestHash;
    }

    public UUID getTransferId() {
        return transferId;
    }
}
