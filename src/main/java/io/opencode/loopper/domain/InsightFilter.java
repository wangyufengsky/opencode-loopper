package io.opencode.loopper.domain;

import java.util.Set;

public record InsightFilter(String projectId, String state, String quality, String archive, String query) {
    public InsightFilter {
        projectId = normalized(projectId); state = normalized(state); quality = normalized(quality);
        archive = normalized(archive); query = normalized(query);
        if (archive == null) archive = "ALL";
        if (!Set.of("ALL", "ACTIVE", "ARCHIVED").contains(archive)
                || quality != null && !Set.of("PASS", "PENDING", "REVIEW_REQUIRED").contains(quality)) throw invalid();
        if (state != null) {
            try { TaskState.valueOf(state); } catch (IllegalArgumentException unknown) { throw invalid(); }
        }
        if (query != null && query.length() > 200) throw invalid();
    }
    public static InsightFilter all() { return new InsightFilter(null, null, null, null, null); }
    private static String normalized(String value) { return value == null || value.isBlank() ? null : value.strip(); }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("洞察筛选条件无效"); }
}
