package io.opencode.loopper.persistence;

/** Bounded audit metadata for tolerant AI output handling; raw model output is deliberately excluded. */
public record AiOutputHandlingEventRow(
        String id,
        String scopeType,
        String scopeId,
        String role,
        String workflowStep,
        String eventType,
        String correctionCategoriesJson,
        String responseFingerprint,
        String createdAt) { }
