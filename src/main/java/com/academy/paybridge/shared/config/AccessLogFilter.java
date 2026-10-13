package com.academy.paybridge.shared.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Writes one log line for every request the application handles, after it has finished:
 * {@code POST /api/transfers/internal -> 201 (14 ms)}.
 * <p>
 * It sits inside {@link RequestIdFilter}, so each line carries the request id and can be matched to any other log
 * line (or error response) from the same request. Bodies and headers are deliberately never logged: they will hold
 * personal and security data (API keys arrive in Step 13).
 * <p>
 * Level by outcome: 5xx is ERROR, 4xx is WARN, everything else INFO. Health checks are DEBUG when they succeed,
 * because the front end and the hosting platform poll them every few seconds and would drown the real traffic.
 * Like {@link RequestIdFilter}, this is registered through a {@code FilterRegistrationBean}, never as a bean.
 */
public class AccessLogFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(AccessLogFilter.class);

    /**
     * Set by the authentication filter to the NAME of the API key that made the request (never the key itself), so
     * each log line says who called.
     */
    public static final String CLIENT_ATTRIBUTE = "paybridge.client";

    private static final String HEALTH_CHECK_PREFIX = "/actuator";
    private static final int MAX_TARGET_LENGTH = 300;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        long startedAt = System.nanoTime();
        int status = HttpServletResponse.SC_INTERNAL_SERVER_ERROR; // what the caller gets if the chain throws
        try {
            filterChain.doFilter(request, response);
            status = response.getStatus();
        } finally {
            long millis = (System.nanoTime() - startedAt) / 1_000_000;
            logRequest(request, status, millis);
        }
    }

    private void logRequest(HttpServletRequest request, int status, long millis) {
        String method = request.getMethod();
        String target = target(request);
        String client = clientSuffix(request);

        if (status >= 500) {
            log.error("{} {} -> {} ({} ms){}", method, target, status, millis, client);
        } else if (status >= 400) {
            log.warn("{} {} -> {} ({} ms){}", method, target, status, millis, client);
        } else if (request.getRequestURI().startsWith(HEALTH_CHECK_PREFIX)) {
            log.debug("{} {} -> {} ({} ms){}", method, target, status, millis, client);
        } else {
            log.info("{} {} -> {} ({} ms){}", method, target, status, millis, client);
        }
    }

    /** " client=mobile-app" when the caller is known, otherwise nothing. Control characters are stripped. */
    private static String clientSuffix(HttpServletRequest request) {
        Object client = request.getAttribute(CLIENT_ATTRIBUTE);
        if (client == null) {
            return "";
        }
        return " client=" + client.toString().replaceAll("\\p{Cntrl}", "?");
    }

    /** The path plus query string exactly as sent (still percent-encoded, so it cannot carry a line break), capped in length. */
    private static String target(HttpServletRequest request) {
        String query = request.getQueryString();
        String target = query == null ? request.getRequestURI() : request.getRequestURI() + "?" + query;
        return target.length() > MAX_TARGET_LENGTH ? target.substring(0, MAX_TARGET_LENGTH) + "..." : target;
    }
}
