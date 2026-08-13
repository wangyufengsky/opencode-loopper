package io.opencode.loopper.persistence;

/** Immutable production-Java diff hashes captured immediately before a Stage first runs. */
public record StageJavaBaselineRow(String stageId, String taskId, String snapshotJson,
                                   String snapshotSha256, String createdAt) { }
