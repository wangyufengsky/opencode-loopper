package io.opencode.loopper.persistence;

public record SessionUsageRow(String id, String taskId, String executionSessionId, String judgeRunId, String externalMessageId,
                              String idempotencyKey, String providerId, String modelId, Long inputTokens,
                              Long outputTokens, Long totalTokens, String costAmount, String currency,
                              boolean reliable, String observedAt) { }
