package io.opencode.loopper.persistence;

/** One Decomposer Session and its validated immutable plan. */
public record TaskDecompositionRow(
        String id, String designerSessionId, String requirementRevisionId, String state,
        String resultType, String normalizedGoal, String globalConstraintsJson, String planJson,
        String externalSessionId, String externalSessionState, int repairCount, int transportRetryCount,
        long sourceDraftVersion, String lastErrorCode, String lastErrorDetail,
        String createdAt, String updatedAt, long version,
        String workflowStep, String planningJson, int planningRepairCount) { }
