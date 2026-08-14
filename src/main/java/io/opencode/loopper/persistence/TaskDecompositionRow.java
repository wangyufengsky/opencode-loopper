package io.opencode.loopper.persistence;

import org.apache.ibatis.annotations.AutomapConstructor;

/** One Decomposer Session and its validated immutable plan. */
public record TaskDecompositionRow(
        String id, String designerSessionId, String requirementRevisionId, String state,
        String resultType, String normalizedGoal, String globalConstraintsJson, String planJson,
        String externalSessionId, String externalSessionState, int repairCount, int transportRetryCount,
        long sourceDraftVersion, String lastErrorCode, String lastErrorDetail,
        String createdAt, String updatedAt, long version,
        String workflowStep, String planningJson, int planningRepairCount,
        String planningResponseMode, String planningResponseSchemaId, boolean planningFormatFallbackUsed,
        String finalResponseMode, String finalResponseSchemaId, boolean finalFormatFallbackUsed) {
    @AutomapConstructor public TaskDecompositionRow { }

    public TaskDecompositionRow(String id, String designerSessionId, String requirementRevisionId, String state,
                                String resultType, String normalizedGoal, String globalConstraintsJson, String planJson,
                                String externalSessionId, String externalSessionState, int repairCount, int transportRetryCount,
                                long sourceDraftVersion, String lastErrorCode, String lastErrorDetail,
                                String createdAt, String updatedAt, long version,
                                String workflowStep, String planningJson, int planningRepairCount) {
        this(id, designerSessionId, requirementRevisionId, state, resultType, normalizedGoal,
                globalConstraintsJson, planJson, externalSessionId, externalSessionState, repairCount,
                transportRetryCount, sourceDraftVersion, lastErrorCode, lastErrorDetail, createdAt, updatedAt,
                version, workflowStep, planningJson, planningRepairCount,
                "TEXT_MARKER", null, false, "TEXT_MARKER", null, false);
    }
}
