package com.academy.paybridge.security.web;

import com.academy.paybridge.security.service.AuthenticatedClient;
import com.academy.paybridge.security.service.RateLimiter;
import com.academy.paybridge.shared.exception.ErrorCode;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Limits how fast each API key may call us. It runs right after {@link ApiKeyAuthFilter}, so it knows who the caller is.
 * <p>
 * Two buckets per key: a general one for every request, and a tighter one for submitting transfers (the endpoints that
 * move money). A caller who is over the limit gets {@code 429 Too Many Requests} with a {@code Retry-After} header.
 * Requests with no authenticated caller (open health probes) are not limited here.
 */
public class RateLimitFilter extends OncePerRequestFilter {

    private final RateLimiter apiLimiter;
    private final RateLimiter transferLimiter;
    private final RateLimiter webhookLimiter;
    private final MeterRegistry meters;

    public RateLimitFilter(RateLimiter apiLimiter, RateLimiter transferLimiter, RateLimiter webhookLimiter,
                           MeterRegistry meters) {
        this.apiLimiter = apiLimiter;
        this.transferLimiter = transferLimiter;
        this.webhookLimiter = webhookLimiter;
        this.meters = meters;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (AccessPolicy.requirementFor(request.getRequestURI()) == AccessPolicy.Requirement.SIGNED_WEBHOOK) {
            // no API key here, so the caller is identified by address
            RateLimiter.Decision decision = webhookLimiter.tryAcquire(request.getRemoteAddr());
            if (!decision.allowed()) {
                meters.counter("paybridge.ratelimit.rejections", "limiter", "webhooks").increment();
                response.setHeader("Retry-After", String.valueOf(decision.retryAfterSeconds()));
                ProblemJson.write(request, response, ErrorCode.RATE_LIMITED,
                        "Too many requests. Try again in " + decision.retryAfterSeconds() + " second(s).");
                return;
            }
            filterChain.doFilter(request, response);
            return;
        }

        Object caller = request.getAttribute(ApiKeyAuthFilter.CLIENT_ATTRIBUTE);
        if (!(caller instanceof AuthenticatedClient client)) {
            filterChain.doFilter(request, response);
            return;
        }

        String bucketKey = client.keyId().toString();

        RateLimiter.Decision decision = apiLimiter.tryAcquire(bucketKey);
        String limitHit = "api";
        if (decision.allowed() && isTransferSubmission(request)) {
            decision = transferLimiter.tryAcquire(bucketKey);
            limitHit = "transfers";
        }

        if (!decision.allowed()) {
            meters.counter("paybridge.ratelimit.rejections", "limiter", limitHit).increment();
            response.setHeader("Retry-After", String.valueOf(decision.retryAfterSeconds()));
            ProblemJson.write(request, response, ErrorCode.RATE_LIMITED,
                    "Too many requests. Try again in " + decision.retryAfterSeconds() + " second(s).");
            return;
        }

        filterChain.doFilter(request, response);
    }

    private static boolean isTransferSubmission(HttpServletRequest request) {
        if (!"POST".equalsIgnoreCase(request.getMethod())) {
            return false;
        }
        String path = AccessPolicy.normalise(request.getRequestURI());
        return path != null && (path.equals("/api/transfers") || path.startsWith("/api/transfers/")
                || path.equals("/api/remittances") || path.equals("/api/fx/quotes"));
    }
}
