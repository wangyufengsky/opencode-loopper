package io.opencode.loopper.persistence;

public record TaskExecutionCycleRow(String id, String taskId, int ordinal, String kind, String state,
                                    String startStageId, Integer startStageOrdinal, String supplementalPrompt,
                                    String budgetJson, String failureCode, String failureMessage,
                                    String authorizedAt, String startedAt, String endedAt, long version) { }
