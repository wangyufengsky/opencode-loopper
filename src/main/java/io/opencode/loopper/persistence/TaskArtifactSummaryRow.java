package io.opencode.loopper.persistence;

public record TaskArtifactSummaryRow(
        String id, String taskId, String attemptId, String judgeRunId, String kind,
        String name, String contentType, String metadataSummaryJson, long contentBytes,
        String createdAt) { }
