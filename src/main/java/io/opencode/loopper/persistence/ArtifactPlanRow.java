package io.opencode.loopper.persistence;

import org.apache.ibatis.annotations.AutomapConstructor;

public record ArtifactPlanRow(String id, String designerSessionId, String taskProfileId, String kind,
                              String state, String planJson, String planSha256,
                              String createdAt, String updatedAt, long version) {
    @AutomapConstructor public ArtifactPlanRow { }
}
