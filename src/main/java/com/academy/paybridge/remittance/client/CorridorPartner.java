package com.academy.paybridge.remittance.client;

/**
 * The seam between PayBridge and a corridor partner's own system. The webhook is the FAST way to learn that the sender's money
 * arrived; this is the SAFE way: when a confirmation never comes, PayBridge asks. Both end in the same idempotent step.
 */
public interface CorridorPartner {

    /**
     * What the partner says about the money for one remittance, by our reference.
     *
     * @throws PartnerUnavailableException if the partner cannot be reached (that means "unknown", never "not funded")
     */
    FundingStatus fetchFunding(String partnerCode, String reference);

    enum FundingState {
        /** The partner has nothing for this reference (yet). */
        UNKNOWN,
        /** The partner holds the sender's money. */
        FUNDED,
        /** The partner says the funding failed (the sender's payment was declined, for example). */
        FAILED
    }

    /** @param amountMinor the US cents the partner says it holds; null unless FUNDED */
    record FundingStatus(FundingState state, Long amountMinor, String currency, String partnerReference, String message) {

        public static FundingStatus unknown() {
            return new FundingStatus(FundingState.UNKNOWN, null, null, null, null);
        }
    }
}
