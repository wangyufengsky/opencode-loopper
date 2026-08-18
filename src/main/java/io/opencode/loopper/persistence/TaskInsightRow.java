package io.opencode.loopper.persistence;

public record TaskInsightRow(String id, String title, String state, String createdAt, String updatedAt,
                             int attemptCount, int attemptedStageCount, int judgeCount, int judgedRoleCount,
                             int verificationCount, int verificationPassedCount,
                             int requirementJudgePassed, int riskJudgePassed,
                             Long inputTokens, Long outputTokens, Long totalTokens, long unknownUsageCount) { }
