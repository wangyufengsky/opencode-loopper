package io.opencode.loopper.persistence;
import org.apache.ibatis.annotations.AutomapConstructor;

public record TaskRow(String id, String projectId, String loopDraftId, String title, String state,
                      String worktreePath, String branchName, String sourceBranch, String baselineCommit,
                      String createdAt, String updatedAt, long version,
                      String taskProfileId, String rolePackId, String rolePackVersion,
                      String executionMode, String workspacePolicy) {
    @AutomapConstructor
    public TaskRow { }

    /** Compatibility constructor for tests and historical call sites which do not prepare Git task branches. */
    public TaskRow(String id, String projectId, String loopDraftId, String title, String state,
                   String worktreePath, String branchName, String baselineCommit,
                   String createdAt, String updatedAt, long version) {
        this(id, projectId, loopDraftId, title, state, worktreePath, branchName, null, baselineCommit,
                createdAt, updatedAt, version, null, null, null, "LEGACY_AGGREGATE", null);
    }

    public TaskRow(String id, String projectId, String loopDraftId, String title, String state,
                   String worktreePath, String branchName, String sourceBranch, String baselineCommit,
                   String createdAt, String updatedAt, long version) {
        this(id, projectId, loopDraftId, title, state, worktreePath, branchName, sourceBranch, baselineCommit,
                createdAt, updatedAt, version, null, null, null, "LEGACY_AGGREGATE", null);
    }

    public TaskRow(String id, String projectId, String loopDraftId, String title, String state,
                   String worktreePath, String branchName, String sourceBranch, String baselineCommit,
                   String createdAt, String updatedAt, long version,
                   String taskProfileId, String rolePackId, String rolePackVersion) {
        this(id, projectId, loopDraftId, title, state, worktreePath, branchName, sourceBranch, baselineCommit,
                createdAt, updatedAt, version, taskProfileId, rolePackId, rolePackVersion,
                "LEGACY_AGGREGATE", null);
    }
}
