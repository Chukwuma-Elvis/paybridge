package com.academy.paybridge.transfer.service;

import com.academy.paybridge.shared.money.Money;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;

/** Works out the fee for an external transfer from the configured bands. Internal transfers are always free. */
@Component
public class FeeCalculator {

    private final List<TransferProperties.FeeBand> bands;

    public FeeCalculator(TransferProperties properties) {
        if (properties.externalFees() == null || properties.externalFees().isEmpty()) {
            throw new IllegalStateException("paybridge.transfer.external-fees must contain at least one band");
        }
        // bounded bands first (smallest to largest), the open-ended band last
        this.bands = properties.externalFees().stream()
                .sorted(Comparator.comparing(
                        (TransferProperties.FeeBand band) -> band.upToMinor() == null ? Long.MAX_VALUE : band.upToMinor()))
                .toList();
        if (this.bands.get(this.bands.size() - 1).upToMinor() != null) {
            throw new IllegalStateException("The last fee band must have no up-to-minor, so every amount has a fee");
        }
    }

    public Money feeFor(Money amount) {
        long kobo = amount.minorUnits();
        for (TransferProperties.FeeBand band : bands) {
            if (band.upToMinor() == null || kobo <= band.upToMinor()) {
                return Money.ofMinor(band.feeMinor(), amount.currency());
            }
        }
        throw new IllegalStateException("unreachable: the last band has no upper limit");
    }
}
