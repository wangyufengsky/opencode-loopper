package io.opencode.loopper.persistence;

public record LocalSyncConflictSessionRow(
        String id,
        String taskId,
        String sourceRoot,
        String baselineCommit,
        String taskCommit,
        String sourceHead,
        String state,
        int conflictCount,
        int resolvedCount,
        String backupDir,
        String recoveryLogJson,
        String verificationEvidenceJson,
        String errorMessage,
        String createdAt,
        String updatedAt,
        long version) { }
