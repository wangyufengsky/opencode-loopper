package io.opencode.loopper.persistence;

import org.apache.ibatis.annotations.AutomapConstructor;

/** One dependency-ordered vertical package and its independent Designer state. */
public record DesignWorkPackageRow(
        String id, String designerSessionId, String requirementRevisionId, String decompositionId,
        String packageId, int ordinal, String title, String objective,
        String scopeInJson, String scopeOutJson, String dependenciesJson, String deliverablesJson,
        String acceptanceIntentJson, String requirementRefsJson, String state,
        String designerExternalSessionId, String designerExternalSessionState,
        String designMessageId, int designRevision, int redesignCount, int designerTransportRetryCount,
        String compilerSummary, String handoffSummary, String lastErrorCode, String lastErrorDetail,
        Integer approvedDesignRevision, int discussionRoundCount, String invalidatedByPackageId, String approvedAt,
        String createdAt, String updatedAt, long version,
        int planRevision, String correctionOfPackageId, String supersededAt) {
    @AutomapConstructor public DesignWorkPackageRow { }

    public DesignWorkPackageRow(
            String id, String designerSessionId, String requirementRevisionId, String decompositionId,
            String packageId, int ordinal, String title, String objective,
            String scopeInJson, String scopeOutJson, String dependenciesJson, String deliverablesJson,
            String acceptanceIntentJson, String requirementRefsJson, String state,
            String designerExternalSessionId, String designerExternalSessionState,
            String designMessageId, int designRevision, int redesignCount, int designerTransportRetryCount,
            String compilerSummary, String handoffSummary, String lastErrorCode, String lastErrorDetail,
            Integer approvedDesignRevision, int discussionRoundCount, String invalidatedByPackageId, String approvedAt,
            String createdAt, String updatedAt, long version) {
        this(id, designerSessionId, requirementRevisionId, decompositionId, packageId, ordinal, title, objective,
                scopeInJson, scopeOutJson, dependenciesJson, deliverablesJson, acceptanceIntentJson,
                requirementRefsJson, state, designerExternalSessionId, designerExternalSessionState,
                designMessageId, designRevision, redesignCount, designerTransportRetryCount, compilerSummary,
                handoffSummary, lastErrorCode, lastErrorDetail, approvedDesignRevision, discussionRoundCount,
                invalidatedByPackageId, approvedAt, createdAt, updatedAt, version, 1, null, null);
    }
}
