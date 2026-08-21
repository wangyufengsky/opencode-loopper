package io.opencode.loopper.persistence;

/** Provider-reported token total for one remote model Session and one local display scope. */
public record ModelTokenUsageRow(
        String id,
        String designerSessionId,
        String taskId,
        String externalSessionId,
        Long inputTokens,
        Long outputTokens,
        Long totalTokens,
        boolean reliable,
        boolean complete,
        String observedAt) { }
