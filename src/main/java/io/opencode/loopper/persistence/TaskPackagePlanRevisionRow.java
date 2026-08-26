package io.opencode.loopper.persistence;

import org.apache.ibatis.annotations.AutomapConstructor;

public record TaskPackagePlanRevisionRow(
        String id, String taskId, String designerSessionId, String requirementRevisionId,
        int revision, String state, String origin, String planJson, String impactJson,
        String externalSessionId, String externalSessionState, String lastErrorCode, String lastErrorDetail,
        String baseCheckpointId, long baseTaskVersion, String basePackageRunId, long basePackageVersion,
        String createdAt, String updatedAt, String approvedAt, String supersededAt, long version) {
    @AutomapConstructor public TaskPackagePlanRevisionRow { }
    public TaskPackagePlanRevisionRow(String id, String taskId, String designerSessionId,
                                      String requirementRevisionId, int revision, String state,
                                      String planJson, String impactJson, String createdAt,
                                      String approvedAt, String supersededAt, long version) {
        this(id, taskId, designerSessionId, requirementRevisionId, revision, state,
                "INITIAL", planJson, impactJson, null, null, null, null,
                null, 0, null, 0, createdAt, createdAt, approvedAt, supersededAt, version);
    }
}
