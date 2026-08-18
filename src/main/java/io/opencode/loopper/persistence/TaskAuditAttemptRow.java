package io.opencode.loopper.persistence;

/** Attempt projection with its first execution session, assembled in one bounded query. */
public record TaskAuditAttemptRow(
        String id, String taskId, String stageId, String executionCycleId, int ordinal,
        String state, String failureKind, String summary, String createdAt, String endedAt,
        String sessionId) { }
