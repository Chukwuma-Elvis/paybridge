package com.academy.paybridge.fx.domain;

import com.academy.paybridge.fx.api.Corridor;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * One exchange rate as it was received. Rows are only ever added: the database refuses updates and deletes.
 */
@Entity
@Immutable
@Table(name = "fx_rate")
@EntityListeners(AuditingEntityListener.class)
public class FxRate {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private Corridor corridor;

    @Column(name = "mid_rate", nullable = false, updatable = false, precision = 18, scale = 6)
    private BigDecimal midRate;

    @Column(nullable = false, updatable = false)
    private String source;

    @Column(name = "retrieved_at", nullable = false, updatable = false)
    private Instant retrievedAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private RateStatus status;

    @Column(name = "approves_rate_id", updatable = false)
    private UUID approvesRateId;

    @Column(updatable = false)
    private String note;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected FxRate() {
    }

    public FxRate(Corridor corridor, BigDecimal midRate, String source, Instant retrievedAt, RateStatus status,
                  UUID approvesRateId, String note) {
        this.corridor = corridor;
        this.midRate = midRate;
        this.source = source;
        this.retrievedAt = retrievedAt;
        this.status = status;
        this.approvesRateId = approvesRateId;
        this.note = note;
    }

    public UUID getId() {
        return id;
    }

    public Corridor getCorridor() {
        return corridor;
    }

    public BigDecimal getMidRate() {
        return midRate;
    }

    public String getSource() {
        return source;
    }

    public Instant getRetrievedAt() {
        return retrievedAt;
    }

    public RateStatus getStatus() {
        return status;
    }

    public UUID getApprovesRateId() {
        return approvesRateId;
    }

    public String getNote() {
        return note;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
