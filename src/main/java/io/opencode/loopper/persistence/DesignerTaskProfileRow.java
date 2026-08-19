package io.opencode.loopper.persistence;

import org.apache.ibatis.annotations.AutomapConstructor;

public record DesignerTaskProfileRow(
        String id, String designerSessionId, String requirementRevisionId, String state,
        String intent, String workflowTemplate, String mutationMode, String artifactKindsJson,
        String technologiesJson, String testPolicy, String executionStrategy,
        String rolePackId, String rolePackVersion, int confidence, String evidenceJson,
        String resolutionSource, int decisionRequired, String createdAt, String updatedAt, long version) {
    @AutomapConstructor public DesignerTaskProfileRow { }
}
