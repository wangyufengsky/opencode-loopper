package io.opencode.loopper.service;

import java.util.List;
import java.util.Set;

final class TaskProfileConfidence {
    private static final Set<String> UNAVAILABLE_SOURCES = Set.of(
            "ROUTER", "ROUTER_FALLBACK", "USER_SELECTION_PENDING", "USER_OVERRIDE", "LEGACY");

    private TaskProfileConfidence() { }

    static boolean available(String resolutionSource, int confidence, List<String> evidence) {
        if (resolutionSource == null || confidence <= 0 || UNAVAILABLE_SOURCES.contains(resolutionSource)) return false;
        return evidence == null || evidence.stream()
                .noneMatch(item -> item != null && item.startsWith("router-error="));
    }
}
