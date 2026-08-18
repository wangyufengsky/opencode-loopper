package io.opencode.loopper.persistence;

import org.apache.ibatis.annotations.AutomapConstructor;

/** Latest persisted Designer session for one draft, including archive and confirmed-task projections. */
public record DesignerSessionHistoryRow(String id, String projectId, String projectName,
                                        String state, String workflowPhase,
                                        String createdAt, String updatedAt,
                                        String draftId, String draftStatus, String goal,
                                        Integer requirementRevision, String activeWorkPackageId,
                                        int archived, String archivedAt,
                                        String taskId, String taskState) {
    @AutomapConstructor
    public DesignerSessionHistoryRow { }
}
