package io.opencode.loopper.persistence;

public record AppSettingsRow(
        int id,
        String cliPath,
        String allowedRoot,
        String providerId,
        String modelId,
        int maxTaskAttempts,
        int attemptTimeoutMinutes,
        int autoApprove,
        String updatedAt) { }
