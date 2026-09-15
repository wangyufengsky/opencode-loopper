package io.opencode.loopper.api;

import io.opencode.loopper.template.SnapshotReview;
import java.lang.reflect.*;
import java.util.*;

/** Schema is derived only from the closed server-owned candidate records. */
final class SnapshotReviewSchemas {
    private SnapshotReviewSchemas() { }
    static List<Map<String, Object>> candidates() {
        return List.of(schema(SnapshotReview.Plan.class), schema(SnapshotReview.Analysis.class), schema(SnapshotReview.Review.class));
    }
    private static Map<String, Object> schema(Type type) {
        if (type instanceof ParameterizedType p && p.getRawType() == List.class)
            return Map.of("type", "array", "items", schema(p.getActualTypeArguments()[0]));
        Class<?> c = (Class<?>) type;
        if (c == String.class) return Map.of("type", "string");
        if (c == int.class) return Map.of("type", "integer");
        if (c.isEnum()) return Map.of("type", "string", "enum", Arrays.stream(c.getEnumConstants()).map(Object::toString).toList());
        Map<String, Object> fields = new LinkedHashMap<>();
        for (var component : c.getRecordComponents()) {
            var field = schema(component.getGenericType());
            if (component.getName().equals("duplicateOf")) field = Map.of("anyOf", List.of(field, Map.of("type", "null")));
            fields.put(component.getName(), field);
        }
        return Map.of("type", "object", "properties", fields, "required", List.copyOf(fields.keySet()), "additionalProperties", false);
    }
}
