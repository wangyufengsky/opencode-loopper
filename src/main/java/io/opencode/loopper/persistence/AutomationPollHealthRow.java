package io.opencode.loopper.persistence;

public record AutomationPollHealthRow(String ruleId, long ruleVersion, String status, String lastCheckedAt,
                                     String lastSuccessAt, int consecutiveFailures, String errorCode, String errorMessage) { }
