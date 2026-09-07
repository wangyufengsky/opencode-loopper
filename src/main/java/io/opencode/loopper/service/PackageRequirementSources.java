package io.opencode.loopper.service;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/** Stable bounded source index over the complete immutable requirement; the tail is grouped, never discarded. */
final class PackageRequirementSources {
    record Source(String ref, String text, String sha256) { }
    static Map<String, Source> index(String original) {
        String text = original == null ? "" : original;
        String[] lines = text.split("\n", -1);
        Map<String, Source> sources = new LinkedHashMap<>();
        for (int i = 0; i < Math.min(lines.length, 128); i++) {
            String content = i == 127 ? String.join("\n", java.util.Arrays.copyOfRange(lines, i, lines.length)) : lines[i];
            if (content.isBlank()) continue;
            String ref = "REQ-L%03d".formatted(i + 1);
            sources.put(ref, new Source(ref, content, PackageDesignEvidencePreparation.hash(content.getBytes(StandardCharsets.UTF_8))));
        }
        return java.util.Collections.unmodifiableMap(sources);
    }

    static String prompt(String original) {
        StringBuilder result = new StringBuilder("Frozen original requirement source index (references, not proof of a candidate's interpretation):\n");
        index(original).values().forEach(source -> result.append('[').append(source.ref()).append("] ").append(source.text()).append('\n'));
        return result.toString();
    }
}
