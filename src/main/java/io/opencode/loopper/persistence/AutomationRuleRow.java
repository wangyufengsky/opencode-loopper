package io.opencode.loopper.persistence;

public record AutomationRuleRow(String id, String name, String projectId, String templateVersionId,
                                String triggerType, String state, String approvalMode,
                                String triggerConfigJson, String webhookTokenHash,
                                String lastObservedHead, String createdAt, String updatedAt, long version) { }
