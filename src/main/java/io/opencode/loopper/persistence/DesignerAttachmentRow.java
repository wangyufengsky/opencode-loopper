package io.opencode.loopper.persistence;

public record DesignerAttachmentRow(
        String id, String designerSessionId, String designerMessageId, String submissionId,
        String scopeKey, String workPackageId, String originalFilename, String detectedMediaType,
        long sizeBytes, String sha256, String relativePath, String extractorId, String extractorVersion,
        String extractedMediaType, Long extractedSizeBytes, String extractedSha256,
        String extractedRelativePath, String previewKind, String state,
        String supersededByAttachmentId, String sentAt, String stoppedAt,
        String createdAt, String updatedAt, long version) { }
