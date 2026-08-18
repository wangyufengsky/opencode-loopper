package io.opencode.loopper.persistence;

public record TaskOverviewRow(String id, String projectId, String projectName, String title, String goal,
                              String branchName, String worktreePath, String state,
                              String retryCause, Integer retryOrdinal, String retryCreatedAt, String retryDueAt,
                              Integer retryDelaySeconds, String waitingReasonCode,
                              int hasDesignHistory, int archived, String executionResult,
                              Integer executionCycleOrdinal, String checkpointState,
                              String parentTaskId, String successorTaskId, int attemptCount,
                              int maxTaskAttempts, int maxStageAttempts, String createdAt, String updatedAt) { }
