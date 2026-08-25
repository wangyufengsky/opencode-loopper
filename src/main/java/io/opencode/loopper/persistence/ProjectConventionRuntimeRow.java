package io.opencode.loopper.persistence;

/** Durable progress fingerprint and explicit stop intent for one project-convention generation. */
public record ProjectConventionRuntimeRow(
        String draftId,
        String lastProgressAt,
        String progressFingerprint,
        String stopReason,
        String stopDetail,
        String createdAt,
        String updatedAt,
        long version) { }
