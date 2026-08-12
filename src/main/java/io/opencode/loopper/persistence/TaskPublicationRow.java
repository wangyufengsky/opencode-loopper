package io.opencode.loopper.persistence;

public record TaskPublicationRow(String taskId, String state, String remoteName, String remoteUrl,
                                 String provider, String sourceBranch, String targetBranch,
                                 String taskCommitSha, String commitMessage, String creationRequestedAt,
                                 Long mergeRequestIid, String mergeRequestUrl, String mergeRequestState,
                                 String mergeRequestHeadSha, String mergeCommitSha,
                                 String mergeRequestOpenedAt, String mergedAt, String lastCheckedAt,
                                 String lastCheckError, String createdAt, String updatedAt, long version) { }
