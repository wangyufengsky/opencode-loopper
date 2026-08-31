package io.opencode.loopper.persistence;

import org.apache.ibatis.annotations.AutomapConstructor;

/** One independent read-only compilation of one immutable Designer revision. */
public record LoopSpecCompilationRow(
        String id, String designerSessionId, int designRevision, String state,
        String externalSessionId, String externalSessionState, int repairCount,
        String sourceDesignMessageId, long sourceDraftVersion,
        String lastErrorCode, String lastErrorDetail,
        String createdAt, String updatedAt, long version,
        String workPackageId, int transportRetryCount, String compiledPackageJson,
        String workflowStep, String planningJson, int planningRepairCount,
        String planningResponseMode, String planningResponseSchemaId, boolean planningFormatFallbackUsed,
        String finalResponseMode, String finalResponseSchemaId, boolean finalFormatFallbackUsed,
        String semanticPlanJson, int formatRepairCount, int semanticRepairCount, boolean serverCompiled,
        String compilationSource, String fallbackReason) {
    @AutomapConstructor
    public LoopSpecCompilationRow { }

    public LoopSpecCompilationRow(
            String id, String designerSessionId, int designRevision, String state,
            String externalSessionId, String externalSessionState, int repairCount,
            String sourceDesignMessageId, long sourceDraftVersion,
            String lastErrorCode, String lastErrorDetail,
            String createdAt, String updatedAt, long version,
            String workPackageId, int transportRetryCount, String compiledPackageJson,
            String workflowStep, String planningJson, int planningRepairCount,
            String planningResponseMode, String planningResponseSchemaId, boolean planningFormatFallbackUsed,
            String finalResponseMode, String finalResponseSchemaId, boolean finalFormatFallbackUsed,
            String semanticPlanJson, int formatRepairCount, int semanticRepairCount, boolean serverCompiled) {
        this(id, designerSessionId, designRevision, state, externalSessionId, externalSessionState, repairCount,
                sourceDesignMessageId, sourceDraftVersion, lastErrorCode, lastErrorDetail, createdAt, updatedAt,
                version, workPackageId, transportRetryCount, compiledPackageJson, workflowStep, planningJson,
                planningRepairCount, planningResponseMode, planningResponseSchemaId, planningFormatFallbackUsed,
                finalResponseMode, finalResponseSchemaId, finalFormatFallbackUsed, semanticPlanJson,
                formatRepairCount, semanticRepairCount, serverCompiled, null, null);
    }

    public LoopSpecCompilationRow(String id, String designerSessionId, int designRevision, String state,
                                  String externalSessionId, String externalSessionState, int repairCount,
                                  String sourceDesignMessageId, long sourceDraftVersion,
                                  String lastErrorCode, String lastErrorDetail,
                                  String createdAt, String updatedAt, long version,
                                  String workPackageId, int transportRetryCount, String compiledPackageJson,
                                  String workflowStep, String planningJson, int planningRepairCount) {
        this(id, designerSessionId, designRevision, state, externalSessionId, externalSessionState, repairCount,
                sourceDesignMessageId, sourceDraftVersion, lastErrorCode, lastErrorDetail, createdAt, updatedAt,
                version, workPackageId, transportRetryCount, compiledPackageJson, workflowStep, planningJson,
                planningRepairCount, "TEXT_MARKER", null, false, "TEXT_MARKER", null, false,
                planningJson, planningRepairCount, 0, false, null, null);
    }

    public LoopSpecCompilationRow(String id, String designerSessionId, int designRevision, String state,
                                  String externalSessionId, String externalSessionState, int repairCount,
                                  String sourceDesignMessageId, long sourceDraftVersion,
                                  String lastErrorCode, String lastErrorDetail,
                                  String createdAt, String updatedAt, long version) {
        this(id, designerSessionId, designRevision, state, externalSessionId, externalSessionState, repairCount,
                sourceDesignMessageId, sourceDraftVersion, lastErrorCode, lastErrorDetail,
                createdAt, updatedAt, version, null, 0, null, "FINAL_JSON", null, 0,
                "TEXT_MARKER", null, false, "TEXT_MARKER", null, false,
                null, 0, 0, false, null, null);
    }
}
