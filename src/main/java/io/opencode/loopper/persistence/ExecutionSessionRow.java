package io.opencode.loopper.persistence;
public record ExecutionSessionRow(String id, String taskId, String stageId, String attemptId,
                                  String externalSessionId, String state, String createdAt, String endedAt,
                                  long version) { }
