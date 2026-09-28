package io.opencode.loopper.runtime;

import java.util.*;
import java.util.regex.Pattern;
import tools.jackson.databind.ObjectMapper;

/** Prompt hints use the persisted policy; they never enable a tool or broaden its path grants. */
final class NativeKnowledgeFallback {
    private NativeKnowledgeFallback() { }
    static List<String> readTools(String permissions, Map<String, Object> toolFlags, ObjectMapper json) {
        if (permissions == null) return List.of();
        List<String> result = new ArrayList<>();
        var rules = json.readTree(permissions);
        for (String tool : List.of("read", "glob", "grep")) {
            boolean allowed = false;
            for (var rule : rules) {
                String name = rule.path("permission").asText();
                String wildcard = "\\Q" + name.replace("*", "\\E.*\\Q") + "\\E";
                if (Pattern.matches(wildcard, tool) && "*".equals(rule.path("pattern").asText()))
                    allowed = "allow".equals(rule.path("action").asText());
            }
            if (allowed && !Boolean.FALSE.equals(toolFlags.get(tool))) result.add(tool);
        }
        return List.copyOf(result);
    }
}
