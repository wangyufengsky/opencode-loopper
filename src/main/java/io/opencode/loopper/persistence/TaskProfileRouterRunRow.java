package io.opencode.loopper.persistence;

import org.apache.ibatis.annotations.AutomapConstructor;

public record TaskProfileRouterRunRow(
        String id, String designerSessionId, String state, String requirementSnapshot,
        String repositoryEvidenceJson, String externalSessionId, String externalSessionState,
        String responseMode, String semanticLabelsJson, String errorCode, String errorDetail,
        String createdAt, String updatedAt, long version,
        String projectStackProfileId, String componentKeysJson, String stackFingerprint) {
    @AutomapConstructor public TaskProfileRouterRunRow { }

    public TaskProfileRouterRunRow(
            String id, String designerSessionId, String state, String requirementSnapshot,
            String repositoryEvidenceJson, String externalSessionId, String externalSessionState,
            String responseMode, String semanticLabelsJson, String errorCode, String errorDetail,
            String createdAt, String updatedAt, long version) {
        this(id, designerSessionId, state, requirementSnapshot, repositoryEvidenceJson, externalSessionId,
                externalSessionState, responseMode, semanticLabelsJson, errorCode, errorDetail,
                createdAt, updatedAt, version, null, "[]", null);
    }
}
