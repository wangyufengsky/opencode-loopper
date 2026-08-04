package io.opencode.loopper.persistence;

/** A read-only design conversation bound to one registered project. */
public record DesignerSessionRow(String id, String projectId, String state, String accessMode,
                                 String createdAt, String updatedAt, long version,
                                 String externalSessionId, String externalSessionState,
                                 String loopDraftId) { }
