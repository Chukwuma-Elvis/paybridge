package com.academy.paybridge.fx.domain;

import com.academy.paybridge.fx.api.Corridor;
import com.academy.paybridge.fx.api.QuoteStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A stored quote. Rows are created and moved to CONSUMED by native single-statement queries (see the repository), because both must
 * be atomic under a race; this class only reads them back.
 */
@Entity
@Immutable
@Table(name = "fx_quote")
public class FxQuote {

    @Id
    private UUID id;

    @Column(nullable = false)
    private String reference;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Corridor corridor;

    @Column(name = "sell_minor", nullable = false)
    private long sellMinor;

    @Column(name = "fee_minor", nullable = false)
    private long feeMinor;

    @Column(name = "buy_minor", nullable = false)
    private long buyMinor;

    @Column(name = "mid_rate", nullable = false, precision = 18, scale = 6)
    private BigDecimal midRate;

    @Column(name = "customer_rate", nullable = false, precision = 18, scale = 6)
    private BigDecimal customerRate;

    @Column(name = "spread_bps", nullable = false)
    private int spreadBps;

    @Column(name = "rate_source", nullable = false)
    private String rateSource;

    @Column(name = "rate_as_of", nullable = false)
    private Instant rateAsOf;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private QuoteStatus status;

    @Column(name = "consumed_by")
    private UUID consumedBy;

    @Column(name = "consumed_at")
    private Instant consumedAt;

    @Column(name = "requested_by", nullable = false)
    private String requestedBy;

    @Column(name = "idempotency_key", nullable = false)
    private String idempotencyKey;

    @Column(name = "request_hash", nullable = false)
    private String requestHash;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected FxQuote() {
    }

    public UUID getId() {
        return id;
    }

    public String getReference() {
        return reference;
    }

    public Corridor getCorridor() {
        return corridor;
    }

    public long getSellMinor() {
        return sellMinor;
    }

    public long getFeeMinor() {
        return feeMinor;
    }

    public long getBuyMinor() {
        return buyMinor;
    }

    public BigDecimal getMidRate() {
        return midRate;
    }

    public BigDecimal getCustomerRate() {
        return customerRate;
    }

    public int getSpreadBps() {
        return spreadBps;
    }

    public String getRateSource() {
        return rateSource;
    }

    public Instant getRateAsOf() {
        return rateAsOf;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public QuoteStatus getStatus() {
        return status;
    }

    public UUID getConsumedBy() {
        return consumedBy;
    }

    public Instant getConsumedAt() {
        return consumedAt;
    }

    public String getRequestedBy() {
        return requestedBy;
    }

    public String getRequestHash() {
        return requestHash;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
