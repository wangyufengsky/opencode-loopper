package io.opencode.loopper.persistence;

public record TaskRetryScheduleRow(
        String id,
        String taskId,
        String stageId,
        String cause,
        int ordinal,
        int delaySeconds,
        String dueAt,
        Integer remainingSeconds,
        String prompt,
        String state,
        String createdAt,
        String updatedAt,
        int version) { }
