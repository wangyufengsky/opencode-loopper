package io.opencode.loopper.service.ppt.agent;

import java.util.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Idempotency compares JSON values independently of object-field ordering. */
final class PptAgentJson {
    private PptAgentJson() { }
    static String canonical(JsonNode node, ObjectMapper json) { return json.writeValueAsString(value(node)); }
    private static Object value(JsonNode node) {
        if (node.isObject()) {
            Map<String,Object> result = new TreeMap<>();
            node.properties().forEach(entry -> result.put(entry.getKey(), value(entry.getValue()))); return result;
        }
        if (node.isArray()) return node.valueStream().map(PptAgentJson::value).toList();
        if (node.isNull()) return null;
        if (node.isBoolean()) return node.asBoolean();
        if (node.isNumber()) return node.decimalValue().stripTrailingZeros();
        return node.asText();
    }
}
