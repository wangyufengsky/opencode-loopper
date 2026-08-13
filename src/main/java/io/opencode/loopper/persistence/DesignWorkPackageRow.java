package io.opencode.loopper.persistence;

/** One dependency-ordered vertical package and its independent Designer state. */
public record DesignWorkPackageRow(
        String id, String designerSessionId, String requirementRevisionId, String decompositionId,
        String packageId, int ordinal, String title, String objective,
        String scopeInJson, String scopeOutJson, String dependenciesJson, String deliverablesJson,
        String acceptanceIntentJson, String requirementRefsJson, String state,
        String designerExternalSessionId, String designerExternalSessionState,
        String designMessageId, int designRevision, int redesignCount, int designerTransportRetryCount,
        String compilerSummary, String handoffSummary, String lastErrorCode, String lastErrorDetail,
        String createdAt, String updatedAt, long version) { }
