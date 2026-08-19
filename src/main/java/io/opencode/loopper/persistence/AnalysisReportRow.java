package io.opencode.loopper.persistence;

import org.apache.ibatis.annotations.AutomapConstructor;

public record AnalysisReportRow(
        String id, String designerSessionId, String taskProfileId, String state, String title,
        String markdown, String evidenceJson, String contentSha256, String sourceSnapshotSha256,
        String errorCode, String errorDetail, String createdAt, String updatedAt, long version,
        String externalSessionId, String externalSessionState, String sourceRequirement,
        String rolePackId, String rolePackVersion) {
    @AutomapConstructor public AnalysisReportRow { }
}
