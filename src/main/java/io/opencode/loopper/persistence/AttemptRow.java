package io.opencode.loopper.persistence;
import org.apache.ibatis.annotations.AutomapConstructor;
public record AttemptRow(String id, String taskId, String stageId, String executionCycleId, int ordinal, String state,
                         String failureKind, String summary, String createdAt, String endedAt, long version) {
    @AutomapConstructor
    public AttemptRow { }

    public AttemptRow(String id, String taskId, String stageId, int ordinal, String state,
                      String failureKind, String summary, String createdAt, String endedAt, long version) {
        this(id, taskId, stageId, null, ordinal, state, failureKind, summary, createdAt, endedAt, version);
    }
}
