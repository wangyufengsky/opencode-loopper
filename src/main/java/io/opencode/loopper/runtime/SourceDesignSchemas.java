package io.opencode.loopper.runtime;

import java.util.List;
import java.util.Map;

final class SourceDesignSchemas {
    private SourceDesignSchemas() { }
    static Map<String, Object> design() {
        var section = object(Map.of("key", text(80), "title", text(200), "markdown", text(48000),
                "paths", array(text(2048), 100), "references", array(reference(), 100)));
        return object(Map.of("title", text(200), "summary", text(4000),
                "sections", array(section, 32), "limitations", array(text(2000), 32)));
    }
    static Map<String, Object> review() {
        var issue = object(Map.of("sectionKey", text(80), "detail", text(4000), "recommendation", text(4000)));
        return object(Map.of("verdict", Map.of("type", "string", "enum", List.of("PASS", "REVISE")),
                "reason", text(4000), "checkedPaths", array(text(2048), 100),
                "references", array(reference(), 100), "issues", array(issue, 64)));
    }
    private static Map<String, Object> reference() {
        return object(Map.of("path", text(2048), "sha256", text(64), "startLine", integer(),
                "endLine", integer(), "quote", text(8000)));
    }
    private static Map<String, Object> integer() { return Map.of("type", "integer", "minimum", 1, "maximum", 10000000); }
    private static Map<String, Object> text(int max) { return Map.of("type", "string", "minLength", 1, "maxLength", max); }
    private static Map<String, Object> array(Map<String, Object> item, int max) { return Map.of("type", "array", "items", item, "maxItems", max); }
    private static Map<String, Object> object(Map<String, Object> properties) {
        return Map.of("type", "object", "properties", properties, "required", List.copyOf(properties.keySet()), "additionalProperties", false);
    }
}
