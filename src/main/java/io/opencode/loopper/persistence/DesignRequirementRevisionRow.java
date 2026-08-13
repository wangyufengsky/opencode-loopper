package io.opencode.loopper.persistence;

/** Immutable complete requirement text plus its bounded model-call budget. */
public record DesignRequirementRevisionRow(
        String id, String designerSessionId, int revision, String sourceMessageId,
        String requirementText, String requirementSegmentsJson, long sourceDraftVersion,
        String state, int modelCallsUsed, int maxModelCalls,
        String createdAt, String updatedAt, long version) { }
