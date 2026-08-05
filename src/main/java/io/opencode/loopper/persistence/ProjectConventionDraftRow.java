package io.opencode.loopper.persistence;

/** A read-only AI proposal that requires an explicit, hash-guarded apply. */
public record ProjectConventionDraftRow(
        String id,
        String projectId,
        String state,
        String externalSessionId,
        String externalSessionState,
        int sourceExists,
        String sourceSha256,
        String sourceContent,
        String proposedContent,
        String errorMessage,
        String createdAt,
        String updatedAt,
        long version) { }
