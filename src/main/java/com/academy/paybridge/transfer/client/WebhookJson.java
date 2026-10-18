package com.academy.paybridge.transfer.client;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Small, careful helpers for reading a provider's JSON. A webhook is data from outside; nothing in it is trusted to be present or
 * to have the right type, so every read here either returns a clean value, returns null, or throws {@link MalformedWebhookException}.
 */
final class WebhookJson {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final int MAX_MESSAGE_LENGTH = 200;

    private WebhookJson() {
    }

    /** Reads the body as a JSON object. */
    static JsonNode parseObject(byte[] rawBody) {
        try {
            JsonNode root = MAPPER.readTree(rawBody);
            if (root == null || !root.isObject()) {
                throw new MalformedWebhookException("The webhook body must be a JSON object");
            }
            return root;
        } catch (JacksonException e) {
            throw new MalformedWebhookException("The webhook body is not valid JSON", e);
        }
    }

    /** A text field, or null if it is missing, null, empty or not a simple value. Numbers are returned as their text. */
    static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull() || !value.isValueNode()) {
            return null;
        }
        String text = value.asString().trim();
        return text.isEmpty() ? null : text;
    }

    /** A whole number such as kobo. Null if absent; malformed if it has a fraction or is not a number. */
    static Long wholeNumber(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        if (value.isIntegralNumber()) {
            return value.asLong();
        }
        if (value.isString()) {
            try {
                return Long.parseLong(value.asString().trim());
            } catch (NumberFormatException e) {
                throw new MalformedWebhookException("The field '" + field + "' is not a whole number");
            }
        }
        throw new MalformedWebhookException("The field '" + field + "' is not a whole number");
    }

    /**
     * An amount written in naira (such as 2000.50) converted to whole kobo (200050). Exact decimal arithmetic, never floating
     * point. A value with more than two decimal places is not an amount of money, so it is refused rather than rounded.
     */
    static Long nairaToKobo(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        BigDecimal naira;
        try {
            naira = value.isNumber() ? value.decimalValue() : new BigDecimal(value.asString().trim());
        } catch (NumberFormatException e) {
            throw new MalformedWebhookException("The field '" + field + "' is not an amount");
        }
        try {
            long kobo = naira.movePointRight(2).setScale(0, RoundingMode.UNNECESSARY).longValueExact();
            if (kobo < 0) {
                throw new MalformedWebhookException("The field '" + field + "' is negative");
            }
            return kobo;
        } catch (ArithmeticException e) {
            throw new MalformedWebhookException("The field '" + field + "' is not a valid amount of money");
        }
    }

    /** The first value that is not null or blank. */
    static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    /** A provider's free text, made safe to store and to log: control characters removed, length limited. */
    static String clean(String message) {
        if (message == null) {
            return null;
        }
        String cleaned = message.replaceAll("\\p{Cntrl}", " ").trim();
        if (cleaned.isEmpty()) {
            return null;
        }
        return cleaned.length() <= MAX_MESSAGE_LENGTH ? cleaned : cleaned.substring(0, MAX_MESSAGE_LENGTH);
    }
}
