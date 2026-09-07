package io.opencode.loopper.persistence;

/** Immutable evidence prepared before dispatch and read without filesystem access during candidate validation. */
public record PackageDesignEvidenceRow(String runId, String policyVersion, String requirementSha256,
                                       String snapshotJson, String snapshotSha256, String createdAt) { }
