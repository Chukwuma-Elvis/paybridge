package com.academy.paybridge.fx.web;

import com.academy.paybridge.fx.api.Corridor;
import com.academy.paybridge.fx.domain.FxRate;
import com.academy.paybridge.fx.domain.RateStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** A stored rate, as shown to admins. */
public record RateResponse(UUID id, Corridor corridor, BigDecimal midRate, String source, Instant retrievedAt, RateStatus status,
                           UUID approvesRateId, String note, Instant createdAt) {

    static RateResponse from(FxRate rate) {
        return new RateResponse(rate.getId(), rate.getCorridor(), rate.getMidRate(), rate.getSource(), rate.getRetrievedAt(),
                rate.getStatus(), rate.getApprovesRateId(), rate.getNote(), rate.getCreatedAt());
    }
}
