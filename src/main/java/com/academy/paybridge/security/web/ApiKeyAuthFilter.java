package com.academy.paybridge.security.web;

import com.academy.paybridge.security.service.ApiKeyService;
import com.academy.paybridge.security.service.AuthenticatedClient;
import com.academy.paybridge.security.service.RateLimiter;
import com.academy.paybridge.security.service.SecurityProperties;
import com.academy.paybridge.security.web.AccessPolicy.Requirement;
import com.academy.paybridge.shared.config.AccessLogFilter;
import com.academy.paybridge.shared.exception.ErrorCode;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Optional;

/**
 * The doorman. Every request passes here before reaching a controller:
 * <ol>
 *   <li>Open addresses (health probes) go straight through.</li>
 *   <li>A client address that has guessed too many wrong keys recently is turned away ({@code 429}), even if its next
 *       guess is right. That is what makes guessing keys pointless.</li>
 *   <li>No key: {@code 401}. A key we do not recognise (or one that was revoked): {@code 401}, and it counts against the
 *       sender's guessing budget.</li>
 *   <li>A valid key that is not allowed here (a client key on an admin address): {@code 403}.</li>
 *   <li>Otherwise the caller is recorded on the request and the request continues.</li>
 * </ol>
 * Registered through a {@code FilterRegistrationBean}, never as a {@code @Component} (see {@code RequestIdFilter}).
 */
public class ApiKeyAuthFilter extends OncePerRequestFilter {

    public static final String API_KEY_HEADER = "X-API-Key";

    /** The request attribute under which later filters find out who the caller is. */
    public static final String CLIENT_ATTRIBUTE = "paybridge.authenticatedClient";

    private final ApiKeyService apiKeyService;
    private final SecurityProperties properties;
    private final RateLimiter failedAuthLimiter;
    private final MeterRegistry meters;

    public ApiKeyAuthFilter(ApiKeyService apiKeyService, SecurityProperties properties,
                            RateLimiter failedAuthLimiter, MeterRegistry meters) {
        this.apiKeyService = apiKeyService;
        this.properties = properties;
        this.failedAuthLimiter = failedAuthLimiter;
        this.meters = meters;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (!properties.enabled()) {
            filterChain.doFilter(request, response);
            return;
        }

        Requirement requirement = AccessPolicy.requirementFor(request.getRequestURI());
        if (requirement == Requirement.PUBLIC) {
            filterChain.doFilter(request, response);
            return;
        }

        String callerAddress = request.getRemoteAddr();
        RateLimiter.Decision guessing = failedAuthLimiter.peek(callerAddress);
        if (!guessing.allowed()) {
            reject(request, response, ErrorCode.RATE_LIMITED, "blocked_address",
                    "Too many invalid API keys from this address. Try again later.", guessing.retryAfterSeconds());
            return;
        }

        String presentedKey = request.getHeader(API_KEY_HEADER);
        if (presentedKey == null || presentedKey.isBlank()) {
            reject(request, response, ErrorCode.UNAUTHORIZED, "missing_key",
                    "An API key is required. Send it in the " + API_KEY_HEADER + " header.", 0);
            return;
        }

        Optional<AuthenticatedClient> client = apiKeyService.authenticate(presentedKey);
        if (client.isEmpty()) {
            failedAuthLimiter.tryAcquire(callerAddress);
            reject(request, response, ErrorCode.UNAUTHORIZED, "invalid_key",
                    "The API key is not valid.", 0);
            return;
        }

        if (requirement == Requirement.ADMIN && !client.get().isAdmin()) {
            reject(request, response, ErrorCode.FORBIDDEN, "forbidden",
                    "This endpoint needs an admin API key.", 0);
            return;
        }

        request.setAttribute(CLIENT_ATTRIBUTE, client.get());
        request.setAttribute(AccessLogFilter.CLIENT_ATTRIBUTE, client.get().name());
        filterChain.doFilter(request, response);
    }

    private void reject(HttpServletRequest request, HttpServletResponse response, ErrorCode code, String reason,
                        String detail, long retryAfterSeconds) throws IOException {
        meters.counter("paybridge.security.rejections", "reason", reason).increment();
        if (code == ErrorCode.UNAUTHORIZED) {
            response.setHeader("WWW-Authenticate", "ApiKey realm=\"paybridge\"");
        }
        if (retryAfterSeconds > 0) {
            response.setHeader("Retry-After", String.valueOf(retryAfterSeconds));
        }
        ProblemJson.write(request, response, code, detail);
    }
}
