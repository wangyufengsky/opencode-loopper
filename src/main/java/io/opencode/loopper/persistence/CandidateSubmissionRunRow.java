package io.opencode.loopper.persistence;

import org.apache.ibatis.annotations.AutomapConstructor;

public record CandidateSubmissionRunRow(
        String id, String designerSessionId, String taskId, String projectId, String ownerType, String ownerId,
        String candidateKind, String workflowStep, long sourceRevision, long ownerVersion, String submissionChannel,
        String contractVersion,
        String runtimeGenerationId, String externalSessionId, String state, int maxAttempts,
        int attemptsUsed, String terminalAttemptId, String createdAt, String updatedAt, long version,
        String closeReason) {
    @AutomapConstructor public CandidateSubmissionRunRow { }

    public CandidateSubmissionRunRow(
            String id, String designerSessionId, String taskId, String projectId, String ownerType, String ownerId,
            String candidateKind, String workflowStep, long sourceRevision, long ownerVersion, String submissionChannel,
            String contractVersion, String runtimeGenerationId, String externalSessionId, String state, int maxAttempts,
            int attemptsUsed, String terminalAttemptId, String createdAt, String updatedAt, long version) {
        this(id, designerSessionId, taskId, projectId, ownerType, ownerId, candidateKind, workflowStep,
                sourceRevision, ownerVersion, submissionChannel, contractVersion, runtimeGenerationId,
                externalSessionId, state, maxAttempts, attemptsUsed, terminalAttemptId, createdAt, updatedAt,
                version, null);
    }
}
