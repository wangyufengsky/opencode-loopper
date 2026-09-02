package io.opencode.loopper.persistence;

import org.apache.ibatis.annotations.AutomapConstructor;

/** One immutable Requirement/Risk evidence generation; role Session retries remain inside the batch. */
public record JudgeReviewBatchRow(
        String id,
        String taskId,
        String executionCycleId,
        String finalAttemptId,
        int generation,
        String state,
        String createdAt,
        String updatedAt,
        String endedAt,
        long version) {
    @AutomapConstructor public JudgeReviewBatchRow { }
}
