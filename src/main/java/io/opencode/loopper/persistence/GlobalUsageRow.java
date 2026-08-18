package io.opencode.loopper.persistence;

public record GlobalUsageRow(Long inputTokens, Long outputTokens, Long totalTokens, long unknownUsageCount) { }
