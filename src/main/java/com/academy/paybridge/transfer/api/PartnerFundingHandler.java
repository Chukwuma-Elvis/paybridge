package com.academy.paybridge.transfer.api;

import java.util.Optional;

/**
 * What the webhook pipeline needs from whoever owns the thing a corridor partner is funding (the remittance module). The pipeline
 * does the generic, security-critical part (signature, de-duplication, recording); this interface is where the business meaning is
 * supplied, so the {@code transfer} module never has to know what a remittance is.
 */
public interface PartnerFundingHandler {

    /** What the partner is expected to have paid for this reference, or empty if we have no such thing from that partner. */
    Optional<ExpectedFunding> expectedFunding(String partner, String reference);

    /**
     * Applies the partner's answer. Safe to call repeatedly with the same answer. A contradiction with the current state (funding
     * arriving for something already cancelled, say) is an INVALID_STATE error, which the pipeline records for manual review.
     *
     * @param funded true when the partner confirms the money arrived, false when it says funding failed
     */
    FundingResult applyFunding(String partner, String reference, boolean funded, String partnerReference, String message);

    /** The partner's amount or currency disagreed with ours, so nothing was applied: raise it with a person. */
    void flagMismatch(String partner, String reference, String detail);

    record ExpectedFunding(long amountMinor, String currency) {
    }

    enum FundingResult {
        /** Applied (or already applied earlier). */
        APPLIED,
        /** Not applied yet: the thing is not ready (for example still under review). A later check will pick it up. */
        HELD
    }
}
