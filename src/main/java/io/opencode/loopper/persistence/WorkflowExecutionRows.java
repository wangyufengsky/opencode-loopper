package io.opencode.loopper.persistence;

public final class WorkflowExecutionRows {
    private WorkflowExecutionRows() { }
    public record Node(String id, String requirementId, String nodeKey, String definitionJson, String definitionSha256,
                       String state, int attemptCount, String latestAttemptId, long version, String createdAt, String updatedAt) { }
    public record Summary(String id, String nodeKey, String state, int attemptCount, String latestAttemptId, long version, String outcome) { }
    public record Attempt(String id, String nodeRunId, int ordinal, int planRevision, String state,
                          String inputsJson, String inputsSha256, String roleSnapshotJson, String adapterKey,
                          String externalSessionId, long version, String createdAt, String updatedAt) { }
    public record Delivery(String attemptId, String contentJson, String sha256, String outcome, String createdAt) { }
    public record Stop(String attemptId, String kind, String externalSessionId, String evidenceJson, String createdAt) { }
    public record PublicInputs(String requirementId, int planRevision, String contentJson, String sha256, String createdAt) { }
}
