package com.academy.paybridge.security.web;

import com.academy.paybridge.security.service.SecurityProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * Handles Cross-Origin Resource Sharing (CORS) for browser callers such as the PayBridge demo console.
 * <p>
 * Key rules:
 * <ol>
 *   <li>Preflight ({@code OPTIONS}) requests from allowed origins are answered immediately with {@code 200 OK} and
 *       CORS headers, BEFORE reaching {@link ApiKeyAuthFilter} or rate limiters (browsers never send credentials on
 *       preflight requests).</li>
 *   <li>Allowed origins are strictly validated against {@link SecurityProperties.Cors#allowedOrigins()}.</li>
 *   <li>When the allow-list is empty (the default), no CORS headers are set (deny-by-default).</li>
 *   <li>Response headers {@code X-Request-Id} and {@code Retry-After} are exposed so frontend scripts can read them.</li>
 * </ol>
 */
public class CorsFilter extends OncePerRequestFilter {

    public static final String ALLOWED_METHODS = "GET, POST, PUT, DELETE, OPTIONS";
    public static final String ALLOWED_HEADERS = "Content-Type, X-API-Key, Idempotency-Key, X-Request-Id";
    public static final String EXPOSED_HEADERS = "X-Request-Id, Retry-After";
    public static final String MAX_AGE_SECONDS = "3600";

    private final SecurityProperties.Cors corsProperties;

    public CorsFilter(SecurityProperties.Cors corsProperties) {
        this.corsProperties = corsProperties;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String origin = request.getHeader("Origin");
        List<String> allowedOrigins = corsProperties != null ? corsProperties.parsedAllowedOrigins() : List.of();

        if (origin != null && !allowedOrigins.isEmpty()) {
            boolean isAllowed = isOriginAllowed(origin, allowedOrigins);
            if (isAllowed) {
                response.setHeader("Access-Control-Allow-Origin", origin);
                response.setHeader("Vary", "Origin");
                response.setHeader("Access-Control-Allow-Methods", ALLOWED_METHODS);
                response.setHeader("Access-Control-Allow-Headers", ALLOWED_HEADERS);
                response.setHeader("Access-Control-Expose-Headers", EXPOSED_HEADERS);
                response.setHeader("Access-Control-Max-Age", MAX_AGE_SECONDS);

                if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
                    response.setStatus(HttpServletResponse.SC_OK);
                    return;
                }
            }
        }

        filterChain.doFilter(request, response);
    }

    private boolean isOriginAllowed(String origin, List<String> allowedOrigins) {
        String cleanOrigin = origin.endsWith("/") ? origin.substring(0, origin.length() - 1) : origin;
        for (String allowed : allowedOrigins) {
            if ("*".equals(allowed)) {
                return true;
            }
            String cleanAllowed = allowed.endsWith("/") ? allowed.substring(0, allowed.length() - 1) : allowed;
            if (cleanAllowed.startsWith("*.")) {
                String suffix = cleanAllowed.substring(1);
                if (cleanOrigin.endsWith(suffix) && (cleanOrigin.startsWith("https://") || cleanOrigin.startsWith("http://"))) {
                    return true;
                }
            } else if (cleanAllowed.equalsIgnoreCase(cleanOrigin)) {
                return true;
            }
        }
        return false;
    }
}
