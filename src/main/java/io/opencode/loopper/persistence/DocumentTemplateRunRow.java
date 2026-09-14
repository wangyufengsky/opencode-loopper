package io.opencode.loopper.persistence;

public record DocumentTemplateRunRow(String id, String requestKey, String requestSha256, String projectId,
        String templateId, String templateVersion, String title, String state, String resumeState,
        String branchJson, String snapshotJson, String contractJson, String designerId, String taskId,
        int requirementRevision, String waitingReasonCode, String waitingMessage, int archived,
        String createdAt, String updatedAt, long version) { }
