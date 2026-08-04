package io.opencode.loopper.persistence;
public record ErrorEventRow(String id, String taskId, String stageId, String attemptId, String sessionId,
                            String layer, String code, String message, boolean retryable,
                            String evidenceJson, String occurredAt) { }
