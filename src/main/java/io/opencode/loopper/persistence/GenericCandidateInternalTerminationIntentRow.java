package io.opencode.loopper.persistence;

import org.apache.ibatis.annotations.AutomapConstructor;

/** Durable V57 termination command and its terminal-gate progress. */
public record GenericCandidateInternalTerminationIntentRow(
        String id, String launchId, String candidateRunId,
        String intentKind, String targetLaunchState, String state, String reasonCode,
        boolean ownerCancelRequested,
        boolean archiveWhenComplete,
        long anchorOwnerVersion, String readyAt, String completedAt,
        String lastErrorCode, String lastErrorDetail,
        String createdAt, String updatedAt, long version) {
    @AutomapConstructor public GenericCandidateInternalTerminationIntentRow { }
}
