package io.opencode.loopper.persistence;

public record StageWorkspaceBaselineRow(
        String stageId,
        String taskId,
        String baselineRef,
        String createdAt
) { }
