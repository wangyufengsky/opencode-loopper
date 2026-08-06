package io.opencode.loopper.persistence;

public record BinaryArtifactRow(String id, String taskId, String attemptId, String executionSessionId,
                                String verificationResultId, String kind, String mediaType,
                                String relativePath, String sha256, long sizeBytes,
                                String metadataJson, String createdAt) { }
