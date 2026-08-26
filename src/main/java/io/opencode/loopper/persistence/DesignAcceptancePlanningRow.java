package io.opencode.loopper.persistence;

/** Frozen DesignFact/capability snapshot and deterministic acceptance-solver result. */
public record DesignAcceptancePlanningRow(
        String compilationId, String designerSessionId, String workPackageId, int designRevision,
        String contractVersion, String designSha256, String state, String bindingSource, String factsJson,
        String capabilitiesJson, String bindingJson, String diagnosticsJson,
        String errorCode, String errorDetail, String createdAt, String updatedAt, long version) { }
