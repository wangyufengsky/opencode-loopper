package io.opencode.loopper.persistence;

public record PackageSemanticPreparationRow(String id, String designWorkPackageId, int discussionRevision,
        String remoteId, String requirementSha256, String promptVersion, String reasonsJson, String basePrompt,
        String state, String material, String failureCode, String createdAt, long version) { }
