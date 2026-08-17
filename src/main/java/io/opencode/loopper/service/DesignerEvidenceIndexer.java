package io.opencode.loopper.service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/** Stable, revision-local line references for frozen Designer Markdown. */
@Component
public class DesignerEvidenceIndexer {
    private static final Pattern REF = Pattern.compile("DS-L[0-9]{3,}");

    public Index index(String markdown) {
        Map<String, String> lines = new LinkedHashMap<>();
        if (markdown != null) {
            for (String raw : markdown.replace("\r\n", "\n").split("\n", -1)) {
                String line = raw.strip();
                if (line.isEmpty()) continue;
                lines.put("DS-L%03d".formatted(lines.size() + 1), line);
            }
        }
        return new Index(lines);
    }

    public record Index(Map<String, String> lines) {
        public Index {
            lines = lines == null
                    ? Map.of()
                    : Collections.unmodifiableMap(new LinkedHashMap<>(lines));
        }

        public String promptText() {
            return lines.entrySet().stream().map(entry -> entry.getKey() + " | " + entry.getValue())
                    .collect(java.util.stream.Collectors.joining("\n"));
        }

        public List<String> resolve(List<String> refs) {
            if (refs == null || refs.isEmpty()) {
                throw new BadRequestException("COMPILER_SOURCE_REFS_REQUIRED",
                        "Every business criterion needs at least one DS-L source reference");
            }
            List<String> result = new ArrayList<>();
            for (String ref : refs) {
                if (ref == null || !REF.matcher(ref).matches() || !lines.containsKey(ref)) {
                    throw new BadRequestException("COMPILER_SOURCE_REF_INVALID",
                            "Unknown frozen design source " + ref + "; available refs: "
                                    + String.join(", ", lines.keySet().stream().limit(12).toList()));
                }
                String value = lines.get(ref);
                if (!result.contains(value)) result.add(value);
            }
            return List.copyOf(result);
        }
    }
}
