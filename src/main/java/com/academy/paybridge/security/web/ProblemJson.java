package com.academy.paybridge.security.web;

import com.academy.paybridge.shared.config.RequestIdFilter;
import com.academy.paybridge.shared.exception.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Writes an error reply in the same JSON shape as every other PayBridge error ({@code detail}, {@code instance},
 * {@code status}, {@code title}, {@code code}, {@code requestId}).
 * <p>
 * The security filters run BEFORE Spring MVC, so the usual {@code GlobalExceptionHandler} never sees their rejections.
 * They have to write the reply themselves.
 */
final class ProblemJson {

    private ProblemJson() {
    }

    static void write(HttpServletRequest request, HttpServletResponse response, ErrorCode code, String detail)
            throws IOException {
        HttpStatus status = code.getHttpStatus();
        response.setStatus(status.value());
        response.setContentType("application/problem+json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());

        StringBuilder json = new StringBuilder(256);
        json.append("{\"detail\":").append(quote(detail))
                .append(",\"instance\":").append(quote(request.getRequestURI()))
                .append(",\"status\":").append(status.value())
                .append(",\"title\":").append(quote(status.getReasonPhrase()))
                .append(",\"code\":").append(quote(code.name()));

        String requestId = MDC.get(RequestIdFilter.MDC_KEY);
        if (requestId != null) {
            json.append(",\"requestId\":").append(quote(requestId));
        }
        json.append('}');

        response.getWriter().write(json.toString());
    }

    /** Wraps text in quotes and escapes anything that could break out of a JSON string. */
    static String quote(String text) {
        StringBuilder quoted = new StringBuilder(text.length() + 2).append('"');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> quoted.append("\\\"");
                case '\\' -> quoted.append("\\\\");
                case '\n' -> quoted.append("\\n");
                case '\r' -> quoted.append("\\r");
                case '\t' -> quoted.append("\\t");
                default -> {
                    if (c < 0x20) {
                        quoted.append(String.format("\\u%04x", (int) c));
                    } else {
                        quoted.append(c);
                    }
                }
            }
        }
        return quoted.append('"').toString();
    }
}
