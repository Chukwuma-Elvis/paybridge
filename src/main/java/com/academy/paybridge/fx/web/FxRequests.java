package com.academy.paybridge.fx.web;

import com.academy.paybridge.fx.api.Corridor;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/** The bodies the admin endpoints accept. */
public final class FxRequests {

    private FxRequests() {
    }

    public record CreateQuote(
            @NotNull(message = "must be a known corridor") Corridor corridor,
            @Min(value = 1, message = "must be at least 1") long sellMinor) {
    }

    public record Refresh(@NotNull(message = "must be a known corridor") Corridor corridor) {
    }

    public record ManualRate(
            @NotNull(message = "must be a known corridor") Corridor corridor,
            @NotNull(message = "is required")
            @DecimalMin(value = "0.000001", message = "must be greater than zero")
            @Digits(integer = 12, fraction = 6, message = "may have at most 12 whole digits and 6 decimal places")
            BigDecimal midRate,
            @Size(max = 200, message = "must be at most 200 characters") String note) {
    }

    public record Spread(
            @NotNull(message = "must be a known corridor") Corridor corridor,
            @Min(value = 0, message = "must be at least 0") @Max(value = 2000, message = "must be at most 2000 (20%)") int spreadBps) {
    }
}
