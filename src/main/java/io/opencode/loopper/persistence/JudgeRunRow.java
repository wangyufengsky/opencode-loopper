package io.opencode.loopper.persistence;

import org.apache.ibatis.annotations.AutomapConstructor;

/**
 * One independently prompted, read-only final-review session.  A retry is a
 * new row rather than an overwrite so the task detail keeps the complete
 * decision trail.
 */
public record JudgeRunRow(String id, String taskId, String attemptId, String role, int ordinal,
                          String externalSessionId, String state, String verdict, String reason,
                          String rawOutput, String createdAt, String endedAt, long version,
                          String responseMode, String responseSchemaId) {
    @AutomapConstructor public JudgeRunRow { }

    public JudgeRunRow(String id, String taskId, String attemptId, String role, int ordinal,
                       String externalSessionId, String state, String verdict, String reason,
                       String rawOutput, String createdAt, String endedAt, long version) {
        this(id, taskId, attemptId, role, ordinal, externalSessionId, state, verdict, reason,
                rawOutput, createdAt, endedAt, version, "TEXT_MARKER", null);
    }
}
