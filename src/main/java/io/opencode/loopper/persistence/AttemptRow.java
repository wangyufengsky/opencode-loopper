package io.opencode.loopper.persistence;
public record AttemptRow(String id, String taskId, String stageId, int ordinal, String state,
                         String failureKind, String summary, String createdAt, String endedAt, long version) { }
