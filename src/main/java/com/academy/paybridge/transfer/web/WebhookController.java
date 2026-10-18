package com.academy.paybridge.transfer.web;

import com.academy.paybridge.shared.exception.ErrorCode;
import com.academy.paybridge.shared.exception.PayBridgeException;
import com.academy.paybridge.transfer.client.WebhookProperties;
import com.academy.paybridge.transfer.service.WebhookService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.InputStream;

/**
 * Where payment providers call us: {@code POST /api/webhooks/{provider}}, for example {@code /api/webhooks/paystack}.
 * <p>
 * This address is open to the internet by design: a provider cannot send our API key. Its protection is the provider's SIGNATURE,
 * checked by {@link WebhookService} before anything in the body is read, plus a per-address rate limit in the security filters.
 * <p>
 * Everything that is not a request to retry (a duplicate, an event we ignore, one we cannot match) is answered 200, because an
 * error status makes the provider send it again, and again.
 */
@RestController
@RequestMapping("/api/webhooks")
public class WebhookController {

    private final WebhookService webhookService;
    private final int maxBodyBytes;

    public WebhookController(WebhookService webhookService, WebhookProperties properties) {
        this.webhookService = webhookService;
        this.maxBodyBytes = properties.maxBodyBytes();
    }

    @PostMapping("/{provider}")
    public WebhookAck receive(@PathVariable String provider, HttpServletRequest request) throws IOException {
        byte[] rawBody = readBody(request);
        WebhookService.Result result = webhookService.handle(provider, rawBody, request::getHeader);
        return new WebhookAck(result.name());
    }

    /**
     * Reads the body exactly as sent, and never more than the limit: a hostile caller cannot make us hold a huge body in
     * memory, because we stop reading one byte past the limit.
     */
    private byte[] readBody(HttpServletRequest request) throws IOException {
        long declared = request.getContentLengthLong();
        if (declared > maxBodyBytes) {
            throw tooLarge();
        }
        try (InputStream in = request.getInputStream()) {
            byte[] body = in.readNBytes(maxBodyBytes + 1);
            if (body.length > maxBodyBytes) {
                throw tooLarge();
            }
            return body;
        }
    }

    private PayBridgeException tooLarge() {
        return new PayBridgeException(ErrorCode.PAYLOAD_TOO_LARGE,
                "The webhook body is larger than " + maxBodyBytes + " bytes");
    }
}
