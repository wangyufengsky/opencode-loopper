package io.opencode.loopper.persistence;

import org.apache.ibatis.annotations.AutomapConstructor;

public record TaskExecutionCycleRow(String id, String taskId, int ordinal, String kind, String state,
                                    String startStageId, Integer startStageOrdinal, String supplementalPrompt,
                                    String budgetJson, String failureCode, String failureMessage,
                                    String authorizedAt, String startedAt, String endedAt, long version,
                                    String packageRunId, String cycleType) {
    @AutomapConstructor
    public TaskExecutionCycleRow { }

    public TaskExecutionCycleRow(String id, String taskId, int ordinal, String kind, String state,
                                 String startStageId, Integer startStageOrdinal, String supplementalPrompt,
                                 String budgetJson, String failureCode, String failureMessage,
                                 String authorizedAt, String startedAt, String endedAt, long version) {
        this(id, taskId, ordinal, kind, state, startStageId, startStageOrdinal, supplementalPrompt,
                budgetJson, failureCode, failureMessage, authorizedAt, startedAt, endedAt, version,
                null, "LEGACY");
    }
}
