package io.opencode.loopper.persistence;

public record AutomationRunRow(String id, String ruleId, String triggerType, String idempotencyKey,
                               String state, String draftId, String taskId, String evidenceJson,
                               String detectedAt, String startedAt, String endedAt) { }
