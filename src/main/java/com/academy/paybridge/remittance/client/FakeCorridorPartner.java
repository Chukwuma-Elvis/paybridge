package com.academy.paybridge.remittance.client;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A pretend corridor partner you can script, to provoke every funding case on purpose:
 * <pre>
 *   fund(ref, cents)           the partner holds exactly that much US money for the reference
 *   fundWrongAmount(...)       same, but the figure is whatever you pass (use a different amount to test a mismatch)
 *   fail(ref)                  the partner reports the funding failed
 *   setUnavailable(true)       the partner cannot be reached
 *   (nothing scripted)         UNKNOWN: the partner has nothing for the reference
 * </pre>
 * Used only for the fallback check ({@link CorridorPartner#fetchFunding}); the matching webhook is a signed request you send yourself.
 */
@Component
public class FakeCorridorPartner implements CorridorPartner {

    private final Map<String, FundingStatus> scripted = new ConcurrentHashMap<>();
    private volatile boolean unavailable;

    @Override
    public FundingStatus fetchFunding(String partnerCode, String reference) {
        if (unavailable) {
            throw new PartnerUnavailableException("Simulated outage at the corridor partner");
        }
        return scripted.getOrDefault(reference, FundingStatus.unknown());
    }

    public void fund(String reference, long amountMinor) {
        scripted.put(reference, new FundingStatus(FundingState.FUNDED, amountMinor, "USD", "P-" + reference, null));
    }

    public void fail(String reference) {
        scripted.put(reference, new FundingStatus(FundingState.FAILED, null, null, null, "Simulated: the sender's payment was declined"));
    }

    public void setUnavailable(boolean unavailable) {
        this.unavailable = unavailable;
    }

    public void reset() {
        scripted.clear();
        unavailable = false;
    }
}
