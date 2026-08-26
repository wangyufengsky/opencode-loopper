package io.opencode.loopper.persistence;

public record PackageFactSnapshotRow(
        String id, String taskId, String packageRunId, String checkpointId, String successfulAttemptId,
        String inputTree, String outputTree, String manifestSha256, String diffSha256, String evidenceSha256,
        String provenJson, String acceptedContractJson, String navigationSummary, String taskSpecSha256,
        String createdAt) { }
