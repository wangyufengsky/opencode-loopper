package io.opencode.loopper.runtime;

import java.util.List;
import java.util.Map;

/** Optional V2 extension; the frozen run policy decides whether these fields are required. */
public final class PackageBehaviorSchemas {
    private PackageBehaviorSchemas() { }
    public static Map<String, Object> branches() {
        return Map.of("type", "array", "maxItems", 64, "items", Map.of("type", "object", "additionalProperties", false,
                "required", List.of("scenarioKey", "obligationRefs", "event", "when", "effects"), "properties", Map.of(
                        "scenarioKey", string(), "obligationRefs", Map.of("type", "array", "maxItems", 64, "items", string()),
                        "event", string(), "when", expression(6), "effects", Map.of("type", "object", "maxProperties", 32,
                                "additionalProperties", string()))));
    }
    private static Map<String, Object> expression(int depth) {
        var properties = new java.util.LinkedHashMap<String, Object>();
        properties.put("op", Map.of("type", "string", "enum", depth == 0 ? List.of("eq", "ne", "true", "false") : List.of("eq", "ne", "all", "any", "not", "true", "false")));
        properties.put("variable", Map.of("type", List.of("string", "null"), "maxLength", 64));
        properties.put("value", Map.of("type", List.of("string", "null"), "maxLength", 80));
        properties.put("args", Map.of("type", "array", "maxItems", depth == 0 ? 0 : 32,
                "items", depth == 0 ? Map.of("type", "object", "additionalProperties", false, "properties", Map.of()) : expression(depth - 1)));
        return Map.of("type", "object", "additionalProperties", false, "required", List.of("op", "variable", "value", "args"), "properties", properties);
    }
    private static Map<String, Object> string() { return Map.of("type", "string", "minLength", 1, "maxLength", 80); }
}
