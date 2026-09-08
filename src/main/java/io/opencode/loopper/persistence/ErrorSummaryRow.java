package io.opencode.loopper.persistence;

public record ErrorSummaryRow(String id, String layer, String code, String message, boolean retryable,
                              String stageId, String attemptId, String sessionId, String occurredAt) { }
