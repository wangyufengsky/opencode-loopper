package io.opencode.loopper.persistence;

public record TaskDesignAttachmentRow(
        String id, String taskId, String sourceDesignerAttachmentId, String sourceTaskId,
        String originalFilename, String scopeKey, String workPackageId, String detectedMediaType,
        long sizeBytes, String sha256, String relativePath, String extractorId, String extractorVersion,
        String extractedMediaType, Long extractedSizeBytes, String extractedSha256,
        String extractedRelativePath, String frozenAt) { }
