package io.opencode.loopper.persistence;

/** Immutable evidence attached to a task, verifier attempt, or independent judge run. */
public record TaskArtifactRow(String id, String taskId, String attemptId, String judgeRunId,
                              String kind, String name, String contentType, String content,
                              String metadataJson, String createdAt) { }
