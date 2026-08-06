package io.opencode.loopper.persistence;

public record TaskLineageRow(String childTaskId, String parentTaskId, String recoveryMode,
                             String parentStageId, String workspaceFingerprint, String createdAt) { }
