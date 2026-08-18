package io.opencode.loopper.persistence;

public record TaskWorkspaceCheckpointRow(String id, String taskId, String cycleId, String state,
                                         String snapshotId, String canonicalRoot, String rootFingerprint,
                                         String branchName, String sourceBranch, String baselineCommit,
                                         String checkpointRef, String checkpointCommit, String checkpointTree,
                                         String manifestJson, String manifestSha256, String stashCommit,
                                         String blockerCode, String blockerMessage,
                                         String createdAt, String updatedAt, long version) { }
