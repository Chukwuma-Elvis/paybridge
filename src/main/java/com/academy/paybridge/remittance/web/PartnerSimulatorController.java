package com.academy.paybridge.remittance.web;

import com.academy.paybridge.remittance.domain.Remittance;
import com.academy.paybridge.remittance.repository.RemittanceRepository;
import com.academy.paybridge.remittance.service.RemittanceProcessor;
import com.academy.paybridge.shared.exception.ResourceNotFoundException;
import com.academy.paybridge.transfer.client.Hmac;
import com.academy.paybridge.transfer.client.WebhookProperties;
import com.academy.paybridge.transfer.service.WebhookService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.UUID;

/**
 * Step 31: Partner simulator endpoint for the demo console.
 * Builds and signs a partner funding webhook event on the server side using the configured partner secret,
 * then passes it through the real {@link WebhookService} and drains the remittance processor so the remittance
 * immediately advances through conversion and payout to PAID.
 */
@RestController
@RequestMapping("/api/admin/simulator")
public class PartnerSimulatorController {

    private final RemittanceRepository remittanceRepository;
    private final WebhookService webhookService;
    private final WebhookProperties webhookProperties;
    private final RemittanceProcessor processor;

    public PartnerSimulatorController(RemittanceRepository remittanceRepository,
                                      WebhookService webhookService,
                                      WebhookProperties webhookProperties,
                                      RemittanceProcessor processor) {
        this.remittanceRepository = remittanceRepository;
        this.webhookService = webhookService;
        this.webhookProperties = webhookProperties;
        this.processor = processor;
    }

    public record SimulationRequest(
            @NotBlank String remittanceReference,
            long amountMinor,
            @NotBlank String mode, // "valid", "forged", "repeat", "wrong-amount"
            String eventId
    ) {}

    public record SimulationResponse(
            String status,
            String mode,
            String eventId,
            String message
    ) {}

    @PostMapping("/partner-funding")
    public ResponseEntity<SimulationResponse> simulatePartnerFunding(@Valid @RequestBody SimulationRequest request) {
        Remittance remittance = remittanceRepository.findByReference(request.remittanceReference())
                .orElseThrow(() -> new ResourceNotFoundException("Remittance", request.remittanceReference()));

        String partnerCode = remittance.getPartnerCode().toLowerCase(Locale.ROOT);
        WebhookProperties.Partner partnerConfig = webhookProperties.partners().get(partnerCode);
        String secret = partnerConfig != null && partnerConfig.secretKey() != null && !partnerConfig.secretKey().isBlank()
                ? partnerConfig.secretKey()
                : "test-partner-secret-not-a-real-key";

        String mode = request.mode().toLowerCase(Locale.ROOT);
        String eventId = request.eventId() != null && !request.eventId().isBlank()
                ? request.eventId()
                : "sim-" + UUID.randomUUID();

        long amount = "wrong-amount".equals(mode)
                ? (request.amountMinor() > 0 ? request.amountMinor() : 50L)
                : remittance.getSellMinor();

        String rawBody = ("{\"partner\":\"%s\",\"eventId\":\"%s\",\"type\":\"funding.confirmed\",\"reference\":\"%s\","
                + "\"partnerReference\":\"SIM-%s\",\"amountMinor\":%d,\"currency\":\"USD\",\"message\":\"Simulated partner funding\"}")
                .formatted(partnerCode, eventId, remittance.getReference(), remittance.getReference(), amount);

        byte[] rawBytes = rawBody.getBytes(StandardCharsets.UTF_8);
        String signature;
        if ("forged".equals(mode)) {
            signature = "00badsignature00";
        } else {
            signature = Hmac.hmacSha512Hex(secret, rawBytes);
        }

        // Pass through the real WebhookService
        WebhookService.Result result = webhookService.handle("partner", rawBytes, headerName ->
                "x-partner-signature".equalsIgnoreCase(headerName) ? signature : null);

        // If repeat requested and it wasn't already a duplicate, run again with same eventId
        if ("repeat".equals(mode) && result != WebhookService.Result.DUPLICATE) {
            result = webhookService.handle("partner", rawBytes, headerName ->
                    "x-partner-signature".equalsIgnoreCase(headerName) ? signature : null);
        }

        // If funding was applied, run processor drain to execute FX conversion and credit the recipient account
        if (result == WebhookService.Result.APPLIED) {
            processor.drain(5);
        }

        return ResponseEntity.ok(new SimulationResponse(
                result.name(),
                mode,
                eventId,
                "Funding webhook processed successfully."
        ));
    }
}
