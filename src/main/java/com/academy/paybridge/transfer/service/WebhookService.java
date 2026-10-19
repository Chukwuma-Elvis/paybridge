package com.academy.paybridge.transfer.service;

import com.academy.paybridge.shared.exception.ErrorCode;
import com.academy.paybridge.shared.exception.PayBridgeException;
import com.academy.paybridge.shared.exception.ResourceNotFoundException;
import com.academy.paybridge.transfer.api.PartnerFundingHandler;
import com.academy.paybridge.transfer.api.TransferType;
import com.academy.paybridge.transfer.client.GatewayOutcome;
import com.academy.paybridge.transfer.client.Hmac;
import com.academy.paybridge.transfer.client.ProviderEvent;
import com.academy.paybridge.transfer.client.WebhookProvider;
import com.academy.paybridge.transfer.domain.WebhookStatus;
import com.academy.paybridge.transfer.service.WebhookRecorder.Claim;
import com.academy.paybridge.transfer.service.WebhookRecorder.TransferFacts;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Receives a payment provider's webhook and, if it is genuine, new and consistent with our records, applies it to the transfer.
 * <p>
 * This is the second way (after the reconciler) that PayBridge learns what became of an external transfer. It is faster
 * (the provider tells us the moment it knows) and it is the only way to learn of a REVERSAL after success. Both ways end in
 * the same place, {@link ExternalTransferService#applyProviderResult}, which is safe to call twice, so webhook and reconciler
 * can never conflict.
 * <p>
 * The order of the checks is the point:
 * <ol>
 *   <li><b>Whose is it?</b> An unknown or unconfigured provider is a 404.</li>
 *   <li><b>Is it genuine?</b> The signature is checked on the raw bytes BEFORE anything is read. Wrong: 401.</li>
 *   <li><b>Can we read it?</b> Not the JSON we expect: 400.</li>
 *   <li><b>Is it new?</b> Providers repeat themselves. A repeat is acknowledged and ignored.</li>
 *   <li><b>Do we act on it?</b> Events that are not transfer outcomes are recorded and ignored.</li>
 *   <li><b>Is it ours, and does it agree with us?</b> The transfer must exist, be external, and the amount and currency must
 *       match our record. A payload's amount is never trusted on its own.</li>
 *   <li><b>Apply it.</b> A contradiction with the transfer's state goes to manual review; an unexpected failure is recorded so
 *       the provider's next delivery tries again.</li>
 * </ol>
 * Deliberately NOT {@code @Transactional}: each step runs in its own short transaction (see {@link WebhookRecorder}), so that
 * the record of what happened survives a failure while applying.
 */
@Service
public class WebhookService {

    private static final Logger log = LoggerFactory.getLogger(WebhookService.class);

    /** What happened to a webhook. Everything here is answered with 200, so the provider stops resending. */
    public enum Result {
        APPLIED, DUPLICATE, IGNORED, UNMATCHED, AMOUNT_MISMATCH, MANUAL_REVIEW
    }

    private final Map<String, WebhookProvider> providers;
    private final WebhookRecorder recorder;
    private final ExternalTransferService externalTransferService;
    private final SystemPayoutService payoutService;
    private final List<PartnerFundingHandler> fundingHandlers;
    private final MeterRegistry meters;

    WebhookService(List<WebhookProvider> providers, WebhookRecorder recorder,
                   ExternalTransferService externalTransferService, SystemPayoutService payoutService,
                   ObjectProvider<PartnerFundingHandler> fundingHandlers, MeterRegistry meters) {
        this.providers = providers.stream().collect(Collectors.toMap(WebhookProvider::name, Function.identity()));
        this.recorder = recorder;
        this.externalTransferService = externalTransferService;
        this.payoutService = payoutService;
        this.fundingHandlers = fundingHandlers.orderedStream().toList();
        this.meters = meters;
    }

    /**
     * @param providerName the provider named in the address, for example {@code paystack}
     * @param rawBody      the request body exactly as it arrived
     * @param header       looks up a request header by name
     */
    public Result handle(String providerName, byte[] rawBody, Function<String, String> header) {
        String name = providerName == null ? "" : providerName.toLowerCase(Locale.ROOT);
        WebhookProvider provider = providers.get(name);
        if (provider == null || !provider.isConfigured()) {
            // the name came from the caller, so it is never used as a metric label (anyone could invent endless names)
            count(provider == null ? "unknown" : name, "not_available");
            throw new ResourceNotFoundException("Webhook provider", safe(name));
        }

        if (!provider.hasValidSignature(rawBody, header)) {
            count(name, "bad_signature");
            log.warn("Webhook from '{}' refused: the signature is missing or wrong", name);
            throw new PayBridgeException(ErrorCode.UNAUTHORIZED, "The webhook signature is missing or invalid");
        }

        ProviderEvent event;
        try {
            event = provider.parse(rawBody);
        } catch (PayBridgeException e) {
            count(name, "malformed");
            log.warn("Webhook from '{}' was signed but could not be read: {}", name, e.getMessage());
            throw e;
        }

        Claim claim = recorder.claim(name, event, Hmac.sha256Hex(rawBody));
        switch (claim.kind()) {
            case DUPLICATE -> {
                return done(name, event, Result.DUPLICATE);
            }
            case IN_PROGRESS -> {
                count(name, "in_progress");
                throw new PayBridgeException(ErrorCode.CONFLICT, "This webhook is already being processed. Send it again shortly.");
            }
            case OWNED -> {
                // carry on below
            }
        }

        if (!event.isActionable()) {
            recorder.finish(claim.eventRowId(), WebhookStatus.IGNORED, "Not a transfer outcome: " + safe(event.eventType()));
            return done(name, event, Result.IGNORED);
        }

        if (provider.kind() == WebhookProvider.Kind.PARTNER_FUNDING) {
            return handleFunding(name, event, claim);
        }
        if (event.reference() != null && event.reference().regionMatches(true, 0, SystemPayoutService.PREFIX, 0, SystemPayoutService.PREFIX.length())) {
            return handlePayout(name, event, claim);
        }

        Optional<TransferFacts> facts = event.reference() == null
                ? Optional.empty() : recorder.transferFacts(event.reference());
        if (facts.isEmpty() || facts.get().type() != TransferType.EXTERNAL) {
            recorder.finish(claim.eventRowId(), WebhookStatus.UNMATCHED, facts.isEmpty()
                    ? "No transfer has this reference" : "The reference belongs to an internal transfer");
            return done(name, event, Result.UNMATCHED);
        }

        String disagreement = disagreement(event, facts.get());
        if (disagreement != null) {
            recorder.finish(claim.eventRowId(), WebhookStatus.AMOUNT_MISMATCH, disagreement);
            return done(name, event, Result.AMOUNT_MISMATCH);
        }

        try {
            externalTransferService.applyProviderResult(event.reference(), event.outcome(), event.providerReference(), event.message());
        } catch (PayBridgeException e) {
            if (e.getErrorCode() == ErrorCode.INVALID_STATE) {
                // for example: the provider now says "success" for a transfer we have already refunded
                recorder.finish(claim.eventRowId(), WebhookStatus.MANUAL_REVIEW, e.getMessage());
                return done(name, event, Result.MANUAL_REVIEW);
            }
            recorder.finish(claim.eventRowId(), WebhookStatus.FAILED, e.getMessage());
            count(name, "failed");
            throw e;
        } catch (RuntimeException e) {
            recorder.finish(claim.eventRowId(), WebhookStatus.FAILED, e.getClass().getSimpleName() + ": " + e.getMessage());
            count(name, "failed");
            throw e;
        }

        recorder.finish(claim.eventRowId(), WebhookStatus.APPLIED, null);
        return done(name, event, Result.APPLIED);
    }

    /** A webhook about a system payout (a remittance paying its recipient): same checks, applied to the payout instead of a transfer. */
    private Result handlePayout(String name, ProviderEvent event, Claim claim) {
        String reference = payoutService.canonicalReference(event.reference()).orElse(event.reference());
        Optional<SystemPayoutService.Facts> facts = payoutService.facts(reference);
        if (facts.isEmpty()) {
            recorder.finish(claim.eventRowId(), WebhookStatus.UNMATCHED, "No payout has this reference");
            return done(name, event, Result.UNMATCHED);
        }
        String disagreement = disagreement(event, facts.get().amountMinor(), facts.get().currency());
        if (disagreement != null) {
            recorder.finish(claim.eventRowId(), WebhookStatus.AMOUNT_MISMATCH, disagreement);
            return done(name, event, Result.AMOUNT_MISMATCH);
        }
        try {
            payoutService.applyProviderResult(reference, event.outcome(), event.providerReference(), event.message());
        } catch (PayBridgeException e) {
            if (e.getErrorCode() == ErrorCode.INVALID_STATE) {
                recorder.finish(claim.eventRowId(), WebhookStatus.MANUAL_REVIEW, e.getMessage());
                return done(name, event, Result.MANUAL_REVIEW);
            }
            recorder.finish(claim.eventRowId(), WebhookStatus.FAILED, e.getMessage());
            count(name, "failed");
            throw e;
        } catch (RuntimeException e) {
            recorder.finish(claim.eventRowId(), WebhookStatus.FAILED, e.getClass().getSimpleName() + ": " + e.getMessage());
            count(name, "failed");
            throw e;
        }
        recorder.finish(claim.eventRowId(), WebhookStatus.APPLIED, null);
        return done(name, event, Result.APPLIED);
    }

    /** A corridor partner says the money for a remittance arrived. Same order of checks; the business meaning comes from the handler. */
    private Result handleFunding(String name, ProviderEvent event, Claim claim) {
        String partner = event.partner();
        PartnerFundingHandler handler = fundingHandlers.isEmpty() ? null : fundingHandlers.get(0);
        Optional<PartnerFundingHandler.ExpectedFunding> expected = handler == null || partner == null || event.reference() == null
                ? Optional.empty() : handler.expectedFunding(partner, event.reference());
        if (expected.isEmpty()) {
            recorder.finish(claim.eventRowId(), WebhookStatus.UNMATCHED, "Nothing is waiting for funding with this reference");
            return done(name, event, Result.UNMATCHED);
        }
        boolean funded = event.outcome() == GatewayOutcome.SUCCESS;
        if (funded) {
            String disagreement = disagreement(event, expected.get().amountMinor(), expected.get().currency());
            if (disagreement != null) {
                handler.flagMismatch(partner, event.reference(), disagreement);
                recorder.finish(claim.eventRowId(), WebhookStatus.AMOUNT_MISMATCH, disagreement);
                return done(name, event, Result.AMOUNT_MISMATCH);
            }
        }
        try {
            PartnerFundingHandler.FundingResult result = handler.applyFunding(partner, event.reference(), funded,
                    event.providerReference(), event.message());
            if (result == PartnerFundingHandler.FundingResult.HELD) {
                recorder.finish(claim.eventRowId(), WebhookStatus.MANUAL_REVIEW,
                        "Held: not ready to be funded yet; the funding check will pick it up");
                return done(name, event, Result.MANUAL_REVIEW);
            }
        } catch (PayBridgeException e) {
            if (e.getErrorCode() == ErrorCode.INVALID_STATE) {
                recorder.finish(claim.eventRowId(), WebhookStatus.MANUAL_REVIEW, e.getMessage());
                return done(name, event, Result.MANUAL_REVIEW);
            }
            recorder.finish(claim.eventRowId(), WebhookStatus.FAILED, e.getMessage());
            count(name, "failed");
            throw e;
        } catch (RuntimeException e) {
            recorder.finish(claim.eventRowId(), WebhookStatus.FAILED, e.getClass().getSimpleName() + ": " + e.getMessage());
            count(name, "failed");
            throw e;
        }
        recorder.finish(claim.eventRowId(), WebhookStatus.APPLIED, null);
        return done(name, event, Result.APPLIED);
    }

    /** Null if the event agrees with the amount and currency we expect; otherwise a sentence saying how it differs. */
    private static String disagreement(ProviderEvent event, long amountMinor, String currency) {
        if (event.amountMinor() != null && event.amountMinor() != amountMinor) {
            return "The webhook says " + event.amountMinor() + " minor units but we expect " + amountMinor;
        }
        if (event.currency() != null && !event.currency().equalsIgnoreCase(currency)) {
            return "The webhook says currency " + safe(event.currency()) + " but we expect " + currency;
        }
        return null;
    }

    /** Null if the event agrees with our record; otherwise a sentence saying how it differs. */
    private static String disagreement(ProviderEvent event, TransferFacts facts) {
        if (event.amountMinor() != null && event.amountMinor() != facts.amountMinor()) {
            return "The provider says " + event.amountMinor() + " kobo but the transfer is " + facts.amountMinor() + " kobo";
        }
        if (event.currency() != null && !event.currency().equalsIgnoreCase(facts.currency())) {
            return "The provider says currency " + safe(event.currency()) + " but the transfer is " + facts.currency();
        }
        return null;
    }

    private Result done(String provider, ProviderEvent event, Result result) {
        count(provider, result.name().toLowerCase(Locale.ROOT));
        String line = "Webhook {} {} for {} -> {}";
        switch (result) {
            case AMOUNT_MISMATCH, MANUAL_REVIEW ->
                    log.error(line + " (a person must look at this)", provider, safe(event.eventType()), safe(event.reference()), result);
            case UNMATCHED ->
                    log.warn(line, provider, safe(event.eventType()), safe(event.reference()), result);
            default ->
                    log.info(line, provider, safe(event.eventType()), safe(event.reference()), result);
        }
        return result;
    }

    private void count(String provider, String result) {
        meters.counter("paybridge.webhooks", "provider", provider, "result", result).increment();
    }

    /** Text from the outside, made safe to put in a log line. */
    private static String safe(String text) {
        return text == null ? "-" : text.replaceAll("\\p{Cntrl}", "?");
    }
}
