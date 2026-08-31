package io.opencode.loopper.persistence;

import org.apache.ibatis.annotations.AutomapConstructor;

public record CandidateSubmissionAttemptRow(
        String id, String runId, int ordinal, String idempotencyKey, String requestSha256,
        String outcome, boolean retryable, String problemsJson, String responseJson,
        String canonicalResultSha256, String createdAt) {
    @AutomapConstructor public CandidateSubmissionAttemptRow { }
}
