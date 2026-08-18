package io.opencode.loopper.persistence;

public record TaskSummaryRow(
        String id, String projectId, String projectName, String title, String goalPreview,
        String branchName, String state, String retryCause, String retryDueAt,
        int hasDesignHistory, int archived, int attemptCount, int maxAttempts,
        String createdAt, String updatedAt) { }
