package io.opencode.loopper.persistence;

import org.apache.ibatis.annotations.AutomapConstructor;

public record TaskProfileRouterRunRow(
        String id, String designerSessionId, String state, String requirementSnapshot,
        String repositoryEvidenceJson, String externalSessionId, String externalSessionState,
        String responseMode, String semanticLabelsJson, String errorCode, String errorDetail,
        String createdAt, String updatedAt, long version) {
    @AutomapConstructor public TaskProfileRouterRunRow { }
}
