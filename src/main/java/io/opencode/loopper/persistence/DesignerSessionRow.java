package io.opencode.loopper.persistence;

import org.apache.ibatis.annotations.AutomapConstructor;

/** A read-only design conversation bound to one registered project. */
public record DesignerSessionRow(String id, String projectId, String state, String accessMode,
                                 String createdAt, String updatedAt, long version,
                                 String externalSessionId, String externalSessionState,
                                 String loopDraftId, String workflowPhase,
                                 int designRevision, int redesignCount,
                                 Integer currentRequirementRevision, String activeWorkPackageId,
                                 String discussionScope, int discussionRevision, String candidateSyncState,
                                 String taskId) {
    @AutomapConstructor
    public DesignerSessionRow { }

    public DesignerSessionRow(String id, String projectId, String state, String accessMode,
                              String createdAt, String updatedAt, long version,
                              String externalSessionId, String externalSessionState,
                              String loopDraftId, String workflowPhase,
                              int designRevision, int redesignCount) {
        this(id, projectId, state, accessMode, createdAt, updatedAt, version, externalSessionId,
                externalSessionState, loopDraftId, workflowPhase, designRevision, redesignCount, null, null,
                "REQUIREMENT", 0, "NONE", null);
    }

    public DesignerSessionRow(String id, String projectId, String state, String accessMode,
                              String createdAt, String updatedAt, long version,
                              String externalSessionId, String externalSessionState,
                              String loopDraftId, String workflowPhase,
                              int designRevision, int redesignCount,
                              Integer currentRequirementRevision, String activeWorkPackageId) {
        this(id, projectId, state, accessMode, createdAt, updatedAt, version, externalSessionId,
                externalSessionState, loopDraftId, workflowPhase, designRevision, redesignCount,
                currentRequirementRevision, activeWorkPackageId,
                activeWorkPackageId == null ? "REQUIREMENT" : activeWorkPackageId, 0, "NONE", null);
    }

    public DesignerSessionRow(String id, String projectId, String state, String accessMode,
                              String createdAt, String updatedAt, long version,
                              String externalSessionId, String externalSessionState,
                              String loopDraftId, String workflowPhase,
                              int designRevision, int redesignCount,
                              Integer currentRequirementRevision, String activeWorkPackageId,
                              String discussionScope, int discussionRevision, String candidateSyncState) {
        this(id, projectId, state, accessMode, createdAt, updatedAt, version, externalSessionId,
                externalSessionState, loopDraftId, workflowPhase, designRevision, redesignCount,
                currentRequirementRevision, activeWorkPackageId, discussionScope, discussionRevision,
                candidateSyncState, null);
    }
}
