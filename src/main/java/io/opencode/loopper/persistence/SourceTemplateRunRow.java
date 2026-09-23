package io.opencode.loopper.persistence;

public record SourceTemplateRunRow(String id, String requestKey, String requestSha256, String projectId,
        String templateId, String templateVersion, String title, String state, String resumeState,
        String parametersJson, String contractJson, String snapshotJson, String designerId, String taskId,
        String stopTarget, String waitingReasonCode, String waitingMessage, int archived,
        String createdAt, String updatedAt, long version) { }
