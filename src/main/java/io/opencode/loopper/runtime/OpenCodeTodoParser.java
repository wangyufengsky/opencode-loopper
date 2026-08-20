package io.opencode.loopper.runtime;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import tools.jackson.databind.JsonNode;

/** Converts provider Todo JSON into the bounded, stable Loopper projection. */
final class OpenCodeTodoParser {
    private static final int MAX_TODOS = 64;
    private static final int MAX_CONTENT_UTF8 = 1_024;
    private static final int MAX_TOTAL_UTF8 = 64 * 1_024;

    OpenCodeClient.SessionTodoSnapshot parse(JsonNode body) {
        JsonNode todos = listBody(body);
        if (todos == null) return null;
        List<OpenCodeClient.SessionTodo> result = new ArrayList<>();
        Map<String, Integer> occurrences = new HashMap<>();
        int ordinal = 0;
        int totalBytes = 0;
        boolean truncated = false;
        for (JsonNode todo : todos) {
            if (result.size() >= MAX_TODOS) {
                truncated = true;
                break;
            }
            String rawContent = todo.path("content").asText("");
            String content = truncateUtf8(rawContent, MAX_CONTENT_UTF8);
            if (!content.equals(rawContent)) truncated = true;
            int bytes = content.getBytes(StandardCharsets.UTF_8).length;
            if (totalBytes + bytes > MAX_TOTAL_UTF8) {
                truncated = true;
                break;
            }
            totalBytes += bytes;
            String normalized = normalizeContent(content);
            int occurrence = occurrences.merge(normalized, 1, Integer::sum);
            String rawStatus = todo.path("status").asText("");
            String rawPriority = todo.path("priority").asText("");
            Map<String, Object> metadata = new LinkedHashMap<>(object(todo.path("metadata")));
            metadata.put("rawStatus", rawStatus);
            metadata.put("rawPriority", rawPriority);
            String id = "todo-v2:" + sha256(normalized) + ":" + occurrence;
            result.add(new OpenCodeClient.SessionTodo(id, content, status(rawStatus), priority(rawPriority),
                    ordinal++, metadata));
        }
        if (truncated && !result.isEmpty()) {
            OpenCodeClient.SessionTodo last = result.getLast();
            Map<String, Object> metadata = new LinkedHashMap<>(last.metadata());
            metadata.put("projectionTruncated", true);
            result.set(result.size() - 1, new OpenCodeClient.SessionTodo(last.id(), last.content(), last.status(),
                    last.priority(), last.ordinal(), metadata));
        }
        return new OpenCodeClient.SessionTodoSnapshot(result, truncated,
                truncated ? "OpenCode Todo projection was truncated to Loopper safety bounds" : null);
    }

    private JsonNode listBody(JsonNode body) {
        JsonNode value = body != null && body.isArray() ? body : body == null ? null : body.path("data");
        return value != null && value.isArray() ? value : null;
    }

    private Map<String, Object> object(JsonNode value) {
        if (value == null || !value.isObject()) return Map.of();
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> entry : value.properties()) {
            result.put(entry.getKey(), value(entry.getValue()));
        }
        return java.util.Collections.unmodifiableMap(result);
    }

    private Object value(JsonNode item) {
        if (item == null || item.isNull() || item.isMissingNode()) return null;
        if (item.isObject()) return object(item);
        if (item.isArray()) {
            List<Object> result = new ArrayList<>();
            for (JsonNode child : item) result.add(value(child));
            return java.util.Collections.unmodifiableList(result);
        }
        if (item.isBoolean()) return item.booleanValue();
        if (item.isIntegralNumber()) return item.longValue();
        if (item.isNumber()) return item.decimalValue();
        return item.asText("");
    }

    private String status(String value) {
        return switch (normalize(value).replace('-', '_')) {
            case "pending", "open", "todo" -> "PENDING";
            case "in_progress", "inprogress", "doing" -> "IN_PROGRESS";
            case "completed", "complete", "done" -> "COMPLETED";
            case "cancelled", "canceled" -> "CANCELLED";
            default -> "UNKNOWN";
        };
    }

    private String priority(String value) {
        return switch (normalize(value)) {
            case "high" -> "HIGH";
            case "medium", "normal" -> "MEDIUM";
            case "low" -> "LOW";
            default -> null;
        };
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private String normalizeContent(String value) {
        return (value == null ? "" : value).replace("\r\n", "\n").replace('\r', '\n')
                .trim().replaceAll("\\s+", " ");
    }

    private String truncateUtf8(String value, int maxBytes) {
        if (value == null || value.isEmpty()) return "";
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= maxBytes) return value;
        int end = value.length();
        while (end > 0 && value.substring(0, end).getBytes(StandardCharsets.UTF_8).length > maxBytes) end--;
        return value.substring(0, end);
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest((value == null ? "" : value).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }
}
