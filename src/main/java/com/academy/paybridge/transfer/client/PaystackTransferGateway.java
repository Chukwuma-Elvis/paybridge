package com.academy.paybridge.transfer.client;

import com.academy.paybridge.shared.exception.ErrorCode;
import com.academy.paybridge.shared.exception.PayBridgeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;

import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The real Paystack, behind the same {@link TransferGateway} the fake bank implements. Active only when
 * {@code paybridge.gateway=paystack}; the fake stays the default.
 * <p>
 * The rules this class keeps (they come from {@link TransferGateway}'s contract):
 * <ul>
 *   <li><b>Unknown is never failed.</b> A timeout, a network error, a 5xx, a 429, or a rejected key throws
 *       {@link GatewayUnavailableException}. Only a clear refusal from Paystack becomes {@link GatewayOutcome#FAILED}.</li>
 *   <li><b>Idempotent per reference.</b> Paystack refuses a reference it has seen; we then ask it for that transfer's status and
 *       return that, so asking twice never sends twice.</li>
 *   <li><b>References are lower-cased.</b> Paystack accepts only lowercase letters, digits, {@code -} and {@code _} (16 to 50
 *       characters). The same lowercase text comes back in webhooks, so lookups of payouts ignore case.</li>
 *   <li><b>Nothing sensitive is logged:</b> never the key, never account numbers or names.</li>
 * </ul>
 * <b>Not verified against a live account.</b> Field names and status words follow Paystack's documented API; check them against your
 * first real test-mode calls. Test-mode transfers need the OTP requirement switched off in the dashboard, or they stay at {@code otp}.
 */
@Component
@ConditionalOnProperty(name = "paybridge.gateway", havingValue = "paystack")
public class PaystackTransferGateway implements TransferGateway {

    private static final Logger log = LoggerFactory.getLogger(PaystackTransferGateway.class);

    private final RestClient http;

    @org.springframework.beans.factory.annotation.Autowired
    PaystackTransferGateway(PaystackProperties properties) {
        this(requireKey(properties), buildClient(properties));
    }

    /** For tests: use a client that is already pointed somewhere (a mock server). */
    PaystackTransferGateway(PaystackProperties properties, RestClient http) {
        requireKey(properties);
        this.http = http;
    }

    private static PaystackProperties requireKey(PaystackProperties properties) {
        if (properties.secretKey() == null || properties.secretKey().isBlank()) {
            throw new IllegalStateException("paybridge.gateway=paystack but PAYSTACK_SECRET_KEY is not set");
        }
        return properties;
    }

    private static RestClient buildClient(PaystackProperties properties) {
        HttpClient client = HttpClient.newBuilder().connectTimeout(properties.connectTimeout()).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(client);
        factory.setReadTimeout(properties.readTimeout());
        return RestClient.builder()
                .baseUrl(properties.baseUrl())
                .requestFactory(factory)
                .defaultHeader("Authorization", "Bearer " + properties.secretKey())
                .defaultHeader("Accept", "application/json")
                .build();
    }

    // ---- TransferGateway

    @Override
    public ResolvedAccount resolveAccount(String bankCode, String accountNumber) {
        Reply reply = call(HttpMethod.GET, "/bank/resolve?account_number=" + enc(accountNumber) + "&bank_code=" + enc(bankCode), null);
        if (reply.ok() && reply.body().path("status").asBoolean(false)) {
            String name = WebhookJson.text(reply.body().path("data"), "account_name");
            if (name != null) {
                return new ResolvedAccount(bankCode, accountNumber, name);
            }
        }
        if (reply.isClientError() && reply.status() != 401 && reply.status() != 403 && reply.status() != 429) {
            throw new PayBridgeException(ErrorCode.RESOURCE_NOT_FOUND, "Could not resolve account " + mask(accountNumber) + " at bank " + bankCode);
        }
        throw unavailable("name enquiry", reply);
    }

    @Override
    public String createRecipient(String bankCode, String accountNumber, String accountName) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", "nuban");
        body.put("name", accountName);
        body.put("account_number", accountNumber);
        body.put("bank_code", bankCode);
        body.put("currency", "NGN");
        Reply reply = call(HttpMethod.POST, "/transferrecipient", body);
        if (reply.ok() && reply.body().path("status").asBoolean(false)) {
            String code = WebhookJson.text(reply.body().path("data"), "recipient_code");
            if (code != null) {
                return code;
            }
        }
        // creating a payee is not "a payment that may have happened": anything but success means we simply could not do it now
        throw unavailable("creating a recipient", reply);
    }

    @Override
    public GatewayTransferResult initiateTransfer(TransferInstruction instruction) {
        if (!"NGN".equalsIgnoreCase(instruction.currency())) {
            throw new PayBridgeException(ErrorCode.VALIDATION_FAILED, "Paystack transfers here are in NGN only");
        }
        String reference = paystackReference(instruction.reference());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("source", "balance");
        body.put("amount", instruction.amountMinor());          // kobo, exactly as Paystack wants it
        body.put("recipient", instruction.recipientCode());
        body.put("reference", reference);
        body.put("reason", instruction.narration() == null ? "PayBridge transfer" : instruction.narration());

        Reply reply = call(HttpMethod.POST, "/transfer", body);
        if (reply.ok()) {
            return fromTransferData(reply.body());
        }
        if (reply.isClientError() && reply.status() != 401 && reply.status() != 403 && reply.status() != 429) {
            // Paystack said no. Before believing "nothing was sent", ask whether this reference exists: if an earlier attempt of ours
            // got through (a retry after a lost answer), Paystack may refuse the repeat, and the truth is that earlier transfer.
            // Paystack's wording for a repeated reference is not documented, so we do not depend on it. If this check cannot be
            // answered, that is "unknown" (thrown), never "failed".
            GatewayTransferResult existing = fetchTransferStatus(instruction.reference());
            if (existing.outcome() != GatewayOutcome.NOT_FOUND) {
                return existing;
            }
            // no such transfer: it really was refused and no money moved (invalid recipient, balance too low, and so on)
            return GatewayTransferResult.of(GatewayOutcome.FAILED, null, reply.message());
        }
        throw unavailable("sending a transfer", reply);
    }

    @Override
    public GatewayTransferResult fetchTransferStatus(String reference) {
        Reply reply = call(HttpMethod.GET, "/transfer/verify/" + enc(paystackReference(reference)), null);
        if (reply.ok()) {
            return fromTransferData(reply.body());
        }
        if (reply.status() == 404) {
            return GatewayTransferResult.of(GatewayOutcome.NOT_FOUND, null, "Paystack has no record of this transfer");
        }
        throw unavailable("checking a transfer", reply);
    }

    // ---- reading Paystack's answers

    /** Turns a Paystack transfer response ({@code {"status":true,"data":{"status":"success",...}}}) into our outcome. */
    private GatewayTransferResult fromTransferData(JsonNode body) {
        JsonNode data = body.path("data");
        String status = WebhookJson.text(data, "status");
        String providerReference = WebhookJson.text(data, "transfer_code");
        if (status == null) {
            throw new GatewayUnavailableException("Paystack answered without a transfer status");
        }
        return switch (status.toLowerCase(Locale.ROOT)) {
            case "success" -> GatewayTransferResult.of(GatewayOutcome.SUCCESS, providerReference, "Transferred");
            case "failed" -> GatewayTransferResult.of(GatewayOutcome.FAILED, providerReference,
                    WebhookJson.firstNonBlank(WebhookJson.text(data, "reason"), "The transfer failed at Paystack"));
            case "reversed" -> GatewayTransferResult.of(GatewayOutcome.REVERSED, providerReference, "Reversed by Paystack");
            // final states in which nothing was (or will be) sent: the OTP was never entered, our approval URL did not answer or said no
            case "abandoned", "blocked", "rejected" -> GatewayTransferResult.of(GatewayOutcome.FAILED, providerReference,
                    "Paystack did not send the transfer (" + status.toLowerCase(Locale.ROOT) + ")");
            case "otp" -> {
                log.warn("A Paystack transfer is waiting for an OTP: switch OTP off for transfers in the Paystack dashboard");
                yield GatewayTransferResult.of(GatewayOutcome.PENDING, providerReference, "Waiting for an OTP at Paystack");
            }
            default -> GatewayTransferResult.of(GatewayOutcome.PENDING, providerReference, "Accepted, processing");
        };
    }

    // ---- the HTTP call

    /** What came back, whatever the status. A failure to get ANY answer is thrown as {@link GatewayUnavailableException}. */
    private record Reply(int status, JsonNode body) {
        boolean ok() {
            return status >= 200 && status < 300;
        }

        boolean isClientError() {
            return status >= 400 && status < 500;
        }

        String message() {
            String text = WebhookJson.text(body, "message");
            return WebhookJson.clean(text == null ? "Paystack refused the request (" + status + ")" : text);
        }
    }

    private Reply call(HttpMethod method, String path, Object requestBody) {
        try {
            RestClient.RequestBodySpec spec = http.method(method).uri(path);
            if (requestBody != null) {
                spec.contentType(org.springframework.http.MediaType.APPLICATION_JSON).body(requestBody);
            }
            return spec.exchange((request, response) -> {
                byte[] bytes = response.getBody().readAllBytes();
                JsonNode json;
                try {
                    json = bytes.length == 0 ? WebhookJson.parseObject("{}".getBytes(StandardCharsets.UTF_8)) : WebhookJson.parseObject(bytes);
                } catch (MalformedWebhookException e) {
                    json = WebhookJson.parseObject("{}".getBytes(StandardCharsets.UTF_8));
                }
                return new Reply(response.getStatusCode().value(), json);
            });
        } catch (RestClientException e) {
            throw new GatewayUnavailableException("Could not reach Paystack: " + e.getClass().getSimpleName(), e);
        }
    }

    private static GatewayUnavailableException unavailable(String what, Reply reply) {
        if (reply.status() == 401 || reply.status() == 403) {
            return new GatewayUnavailableException("Paystack rejected our credentials while " + what + ": check PAYSTACK_SECRET_KEY");
        }
        return new GatewayUnavailableException("No usable answer from Paystack while " + what + " (status " + reply.status() + ")");
    }

    // ---- small helpers

    /** Paystack's reference rule: lowercase letters, digits, - and _ only. Ours are already close; this makes them exact. */
    static String paystackReference(String reference) {
        return reference.toLowerCase(Locale.ROOT);
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String mask(String accountNumber) {
        return accountNumber != null && accountNumber.length() > 4 ? "******" + accountNumber.substring(accountNumber.length() - 4) : "****";
    }
}
