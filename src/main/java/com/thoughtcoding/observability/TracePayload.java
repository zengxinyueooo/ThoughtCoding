package com.thoughtcoding.observability;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/** The only serialization boundary for exported content. Never serialize configuration objects. */
final class TracePayload {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> SECRETS = ConcurrentHashMap.newKeySet();
    private static final Pattern CREDENTIAL = Pattern.compile(
            "(?i)((?:api[_-]?key|secret[_-]?key|password|authorization|access[_-]?token)"
                    + "[\"']?\\s*[:=]\\s*[\"']?)([^\\s\"',;}]+)");
    static {
        System.getenv().forEach((key, value) -> {
            if (key.matches("(?i).*(KEY|TOKEN|SECRET|PASSWORD).*")) registerSecret(value);
        });
    }
    static void registerSecret(String secret) {
        if (secret != null && !secret.isBlank()) SECRETS.add(secret);
    }
    static String clean(String value) {
        if (value == null) return "";
        for (String secret : SECRETS) value = value.replace(secret, "[REDACTED]");
        return CREDENTIAL.matcher(value).replaceAll("$1[REDACTED]");
    }
    static String json(Object value) {
        try {
            return clean(JSON.writeValueAsString(value));
        } catch (Exception ignored) {
            return "\"[unserializable]\"";
        }
    }
    static String limited(Object value, int limit) {
        String serialized = json(value);
        if (serialized.length() <= limit) return serialized;
        // Keep chat roles and tool-call linkage parseable even when content must be bounded.
        if (value instanceof List<?> messages && !messages.isEmpty()
                && messages.stream().allMatch(m -> m instanceof Map<?, ?> map && map.containsKey("role"))) {
            List<Map<String, Object>> bounded = new ArrayList<>();
            int contentLimit = Math.max(32, limit / (messages.size() * 4));
            for (Object item : messages) {
                Map<String, Object> copy = new LinkedHashMap<>();
                ((Map<?, ?>) item).forEach((key, field) -> {
                    String name = String.valueOf(key);
                    if ("content".equals(name) && field instanceof String text && text.length() > contentLimit)
                        copy.put(name, text.substring(0, contentLimit) + " [TRUNCATED]");
                    else copy.put(name, field);
                });
                bounded.add(copy);
            }
            // If tool arguments or a very long message list still exceed the bound, retain
            // a role-labelled preview rather than an unparseable diagnostic object.
            String result = json(bounded);
            if (result.length() <= limit) return result;
            return json(List.of(Map.of("role", "system", "content",
                    "[TRUNCATED conversation preview] " + clean(serialized.substring(0, Math.max(0, limit / 2))))));
        }
        return json(Map.of("truncated", true, "original_chars", serialized.length(),
                "preview", serialized.substring(0, limit)));
    }
}
