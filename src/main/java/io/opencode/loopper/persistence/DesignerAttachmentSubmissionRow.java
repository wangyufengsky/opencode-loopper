package io.opencode.loopper.persistence;

public record DesignerAttachmentSubmissionRow(
        String id, String designerSessionId, String scopeKey, String workPackageId,
        String requestSha256, String state, String oldExternalSessionId, String newExternalSessionId,
        String externalMessageId, String errorCode, String errorDetail,
        String createdAt, String updatedAt, long version) { }
