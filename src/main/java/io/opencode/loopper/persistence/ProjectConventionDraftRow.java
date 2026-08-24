package io.opencode.loopper.persistence;

import org.apache.ibatis.annotations.AutomapConstructor;

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
        String normalizationNotice,
        String errorMessage,
        String createdAt,
        String updatedAt,
        long version,
        String projectStackProfileId,
        String stackFingerprint) {
    @AutomapConstructor public ProjectConventionDraftRow { }
    public ProjectConventionDraftRow(
            String id, String projectId, String state, String externalSessionId, String externalSessionState,
            int sourceExists, String sourceSha256, String sourceContent, String proposedContent,
            String normalizationNotice, String errorMessage, String createdAt, String updatedAt, long version) {
        this(id, projectId, state, externalSessionId, externalSessionState, sourceExists, sourceSha256,
                sourceContent, proposedContent, normalizationNotice, errorMessage, createdAt, updatedAt,
                version, null, null);
    }
}
