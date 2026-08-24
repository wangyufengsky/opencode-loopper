package io.opencode.loopper.persistence;

import org.apache.ibatis.annotations.AutomapConstructor;

public record WorkPackageRoleProfileRow(String id, String designerSessionId, String packageId,
                                        String taskProfileId, String rolePackId, String rolePackVersion,
                                        String executionStrategy, String testPolicy, String technologiesJson,
                                        String projectStackProfileId, String componentKeysJson,
                                        String stackFingerprint) {
    @AutomapConstructor public WorkPackageRoleProfileRow { }
    public WorkPackageRoleProfileRow(String id, String designerSessionId, String packageId,
                                     String taskProfileId, String rolePackId, String rolePackVersion,
                                     String executionStrategy, String testPolicy, String technologiesJson) {
        this(id, designerSessionId, packageId, taskProfileId, rolePackId, rolePackVersion,
                executionStrategy, testPolicy, technologiesJson, null, "[]", null);
    }
}
