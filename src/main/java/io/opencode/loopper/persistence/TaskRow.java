package io.opencode.loopper.persistence;
public record TaskRow(String id, String projectId, String loopDraftId, String title, String state,
                      String worktreePath, String branchName, String baselineCommit,
                      String createdAt, String updatedAt, long version) { }
