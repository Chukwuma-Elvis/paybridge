package com.academy.paybridge.security.web;

import org.springframework.web.util.UriUtils;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import java.util.Set;

/**
 * The rulebook: which addresses are open to everyone, which need any valid key, and which need an admin key.
 * <p>
 * Before applying the rules the address is cleaned up the way the web server would clean it: percent-codes are decoded,
 * {@code ;parameters} dropped, repeated slashes collapsed, {@code .} and {@code ..} resolved, and case ignored. Without
 * that, an address such as {@code /actuator/health/../metrics} could look "public" to us while the server routes it to
 * a protected endpoint. Anything we cannot make sense of is treated as admin-only.
 */
final class AccessPolicy {

    enum Requirement {
        /** Anyone may call it. */
        PUBLIC,
        /**
         * Open to the internet because a payment provider cannot send our API key. The request proves itself with the provider's
         * signature instead (checked by the webhook code), and the rate limiter caps how fast one address may call.
         */
        SIGNED_WEBHOOK,
        /** Any valid API key (client or admin). */
        ANY_KEY,
        /** An admin key only. */
        ADMIN
    }

    /** Open addresses: health probes used by hosting platforms and the front end (the Swagger pages are handled below). */
    private static final Set<String> PUBLIC_PATHS =
            Set.of("/actuator/health", "/actuator/health/liveness", "/actuator/health/readiness");

    private AccessPolicy() {
    }

    static Requirement requirementFor(String rawRequestUri) {
        String path = normalise(rawRequestUri);
        if (path == null) {
            return Requirement.ADMIN;
        }
        if (PUBLIC_PATHS.contains(path)) {
            return Requirement.PUBLIC;
        }
        if (path.equals("/swagger-ui.html") || isUnder(path, "/swagger-ui") || isUnder(path, "/v3/api-docs")) {
            // The Swagger page and the API description: a browser cannot attach an API key to loading a page. They only
            // exist when springdoc is switched on; every call made from the page still needs a key.
            return Requirement.PUBLIC;
        }
        if (isUnder(path, "/actuator") || isUnder(path, "/api/admin") || isUnder(path, "/api/dev")) {
            return Requirement.ADMIN;
        }
        if (isUnder(path, "/api/webhooks")) {
            return Requirement.SIGNED_WEBHOOK;
        }
        return Requirement.ANY_KEY;
    }

    /** The cleaned-up address, or null if it cannot be understood. */
    static String normalise(String rawRequestUri) {
        if (rawRequestUri == null) {
            return null;
        }
        try {
            String decoded = UriUtils.decode(rawRequestUri, StandardCharsets.UTF_8)
                    .replace('\\', '/')
                    .replaceAll(";[^/]*", "");

            Deque<String> segments = new ArrayDeque<>();
            for (String segment : decoded.split("/")) {
                if (segment.isEmpty() || segment.equals(".")) {
                    continue;
                }
                if (segment.equals("..")) {
                    segments.pollLast();
                } else {
                    segments.addLast(segment.toLowerCase(Locale.ROOT));
                }
            }
            return "/" + String.join("/", segments);
        } catch (IllegalArgumentException malformedPercentCode) {
            return null;
        }
    }

    private static boolean isUnder(String path, String prefix) {
        return path.equals(prefix) || path.startsWith(prefix + "/");
    }
}
