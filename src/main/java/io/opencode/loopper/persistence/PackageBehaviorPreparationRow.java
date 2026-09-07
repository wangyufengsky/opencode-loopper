package io.opencode.loopper.persistence;

public record PackageBehaviorPreparationRow(String id, String designWorkPackageId, int discussionRevision,
        String remoteId, String requirementSha256, String basePrompt, String contextJson, String promptVersion, String state, String extraction,
        String reviewRemoteId, String reviewJson, String bookJson, String bookSha256, String failureCode,
        String createdAt, long version) { }
