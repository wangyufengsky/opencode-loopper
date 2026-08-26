package io.opencode.loopper.persistence;
import org.apache.ibatis.annotations.AutomapConstructor;
public record StageRow(String id, String taskId, int ordinal, String objective, String allowedPathsJson,
                       String forbiddenPathsJson, String deliverablesJson, String verifiersJson,
                       String state, String createdAt, String updatedAt, long version,
                       String workPackageId, String stageKind, String executionStrategy, String artifactPlanId,
                       String rolePackId, String rolePackVersion, String testPolicy, String technologiesJson,
                       String projectStackProfileId, String componentKeysJson, String stackFingerprint,
                       String packageRunId) {
    @AutomapConstructor
    public StageRow { }

    public StageRow(String id, String taskId, int ordinal, String objective, String allowedPathsJson,
                    String forbiddenPathsJson, String deliverablesJson, String verifiersJson,
                    String state, String createdAt, String updatedAt, long version) {
        this(id, taskId, ordinal, objective, allowedPathsJson, forbiddenPathsJson, deliverablesJson,
                verifiersJson, state, createdAt, updatedAt, version, null, null, null, null,
                null, null, null, "[]", null, "[]", null, null);
    }

    public StageRow(String id, String taskId, int ordinal, String objective, String allowedPathsJson,
                    String forbiddenPathsJson, String deliverablesJson, String verifiersJson,
                    String state, String createdAt, String updatedAt, long version, String workPackageId) {
        this(id, taskId, ordinal, objective, allowedPathsJson, forbiddenPathsJson, deliverablesJson,
                verifiersJson, state, createdAt, updatedAt, version, workPackageId, null, null, null,
                null, null, null, "[]", null, "[]", null, null);
    }

    public StageRow(String id, String taskId, int ordinal, String objective, String allowedPathsJson,
                    String forbiddenPathsJson, String deliverablesJson, String verifiersJson,
                    String state, String createdAt, String updatedAt, long version,
                    String workPackageId, String stageKind, String executionStrategy, String artifactPlanId,
                    String rolePackId, String rolePackVersion, String testPolicy, String technologiesJson) {
        this(id, taskId, ordinal, objective, allowedPathsJson, forbiddenPathsJson, deliverablesJson,
                verifiersJson, state, createdAt, updatedAt, version, workPackageId, stageKind, executionStrategy,
                artifactPlanId, rolePackId, rolePackVersion, testPolicy, technologiesJson, null, "[]", null, null);
    }

    public StageRow(String id, String taskId, int ordinal, String objective, String allowedPathsJson,
                    String forbiddenPathsJson, String deliverablesJson, String verifiersJson,
                    String state, String createdAt, String updatedAt, long version,
                    String workPackageId, String stageKind, String executionStrategy, String artifactPlanId,
                    String rolePackId, String rolePackVersion, String testPolicy, String technologiesJson,
                    String projectStackProfileId, String componentKeysJson, String stackFingerprint) {
        this(id, taskId, ordinal, objective, allowedPathsJson, forbiddenPathsJson, deliverablesJson,
                verifiersJson, state, createdAt, updatedAt, version, workPackageId, stageKind, executionStrategy,
                artifactPlanId, rolePackId, rolePackVersion, testPolicy, technologiesJson, projectStackProfileId,
                componentKeysJson, stackFingerprint, null);
    }
}
