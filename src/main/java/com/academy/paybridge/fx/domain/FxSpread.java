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

import java.time.Instant;
import java.util.UUID;

/** Our margin for a corridor, in basis points, and who set it. Changing it adds a new row; the latest wins. */
@Entity
@Immutable
@Table(name = "fx_spread")
@EntityListeners(AuditingEntityListener.class)
public class FxSpread {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private Corridor corridor;

    @Column(name = "spread_bps", nullable = false, updatable = false)
    private int spreadBps;

    @Column(name = "set_by", nullable = false, updatable = false)
    private String setBy;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected FxSpread() {
    }

    public FxSpread(Corridor corridor, int spreadBps, String setBy) {
        this.corridor = corridor;
        this.spreadBps = spreadBps;
        this.setBy = setBy;
    }

    public UUID getId() {
        return id;
    }

    public Corridor getCorridor() {
        return corridor;
    }

    public int getSpreadBps() {
        return spreadBps;
    }

    public String getSetBy() {
        return setBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
