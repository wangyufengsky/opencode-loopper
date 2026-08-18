package io.opencode.loopper.persistence;

public record TaskStageReadRow(String id, int ordinal, String objective, String state,
                               String allowedPathsJson, String forbiddenPathsJson,
                               String deliverablesJson, String verifiersJson,
                               String createdAt, String updatedAt, String workPackageId,
                               int attemptCount) { }
