package io.opencode.loopper.persistence;

import org.apache.ibatis.annotations.AutomapConstructor;

/** Latest persisted Designer session for one unconfirmed draft, including its recoverable archive projection. */
public record DesignerSessionHistoryRow(String id, String projectId, String projectName,
                                        String state, String workflowPhase,
                                        String createdAt, String updatedAt,
                                        String draftId, String draftStatus, String goal,
                                        Integer requirementRevision, String activeWorkPackageId,
                                        int archived, String archivedAt) {
    @AutomapConstructor
    public DesignerSessionHistoryRow { }
}
