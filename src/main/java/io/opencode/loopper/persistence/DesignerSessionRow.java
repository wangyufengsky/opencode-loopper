package io.opencode.loopper.persistence;

import org.apache.ibatis.annotations.AutomapConstructor;

/** A read-only design conversation bound to one registered project. */
public record DesignerSessionRow(String id, String projectId, String state, String accessMode,
                                 String createdAt, String updatedAt, long version,
                                 String externalSessionId, String externalSessionState,
                                 String loopDraftId, String workflowPhase,
                                 int designRevision, int redesignCount,
                                 Integer currentRequirementRevision, String activeWorkPackageId) {
    @AutomapConstructor
    public DesignerSessionRow { }

    public DesignerSessionRow(String id, String projectId, String state, String accessMode,
                              String createdAt, String updatedAt, long version,
                              String externalSessionId, String externalSessionState,
                              String loopDraftId, String workflowPhase,
                              int designRevision, int redesignCount) {
        this(id, projectId, state, accessMode, createdAt, updatedAt, version, externalSessionId,
                externalSessionState, loopDraftId, workflowPhase, designRevision, redesignCount, null, null);
    }
}
