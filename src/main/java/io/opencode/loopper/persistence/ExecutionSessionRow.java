package io.opencode.loopper.persistence;

import org.apache.ibatis.annotations.AutomapConstructor;

public record ExecutionSessionRow(String id, String taskId, String stageId, String attemptId,
                                  String externalSessionId, String state, String createdAt, String endedAt,
                                  long version, String todoCapability) {
    @AutomapConstructor public ExecutionSessionRow { }

    public ExecutionSessionRow(String id, String taskId, String stageId, String attemptId,
                               String externalSessionId, String state, String createdAt, String endedAt,
                               long version) {
        this(id, taskId, stageId, attemptId, externalSessionId, state, createdAt, endedAt, version, "UNKNOWN");
    }
}
