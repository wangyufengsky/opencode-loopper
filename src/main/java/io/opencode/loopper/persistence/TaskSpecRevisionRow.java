package io.opencode.loopper.persistence;

public record TaskSpecRevisionRow(
        String id, String taskId, int revision, String packageRunId, String specJson,
        String specSha256, int stageCount, String createdAt) { }
