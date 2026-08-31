package io.opencode.loopper.persistence;

import org.apache.ibatis.annotations.AutomapConstructor;

public record CandidateSubmissionRunRow(
        String id, String designerSessionId, String taskDecompositionId, String loopSpecCompilationId,
        String designWorkPackageId,
        String candidateKind, String workflowStep, long sourceRevision, long ownerVersion, String submissionChannel,
        String contractVersion,
        String runtimeGenerationId, String externalSessionId, String state, int maxAttempts,
        int attemptsUsed, String terminalAttemptId, String createdAt, String updatedAt, long version) {
    @AutomapConstructor public CandidateSubmissionRunRow { }
}
