package io.opencode.loopper.persistence;

public record TaskStageReadRow(String id, int ordinal, String objective, String state,
                               String allowedPathsJson, String forbiddenPathsJson,
                               String deliverablesJson, String verifiersJson,
                               String createdAt, String updatedAt, String workPackageId,
                               String stageKind, String executionStrategy, String rolePackId, String rolePackVersion,
                               String testPolicy, String technologiesJson, int attemptCount) { }
