package io.opencode.loopper.persistence;

import org.apache.ibatis.annotations.AutomapConstructor;

public record TaskPackageRunRow(
        String id, String taskId, String planRevisionId, String designWorkPackageId,
        String packageKey, int ordinal, String title, String state, String correctionOfPackageRunId,
        int discussionRevision, int designRevision, Integer acceptedDesignRevision,
        String waitingReasonCode, String createdAt, String updatedAt, long version,
        String resumeCheckpointId) {
    @AutomapConstructor public TaskPackageRunRow { }
    public TaskPackageRunRow(String id, String taskId, String planRevisionId, String designWorkPackageId,
                             String packageKey, int ordinal, String title, String state,
                             String correctionOfPackageRunId, int discussionRevision, int designRevision,
                             Integer acceptedDesignRevision, String waitingReasonCode, String createdAt,
                             String updatedAt, long version) {
        this(id, taskId, planRevisionId, designWorkPackageId, packageKey, ordinal, title, state,
                correctionOfPackageRunId, discussionRevision, designRevision, acceptedDesignRevision,
                waitingReasonCode, createdAt, updatedAt, version, null);
    }
}
