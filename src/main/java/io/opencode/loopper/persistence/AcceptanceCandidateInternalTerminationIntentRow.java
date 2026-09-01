package io.opencode.loopper.persistence;

import org.apache.ibatis.annotations.AutomapConstructor;

/** Durable external intent and exact owner anchors for one internal launch. */
public record AcceptanceCandidateInternalTerminationIntentRow(
        String id, String launchId, String designerSessionId, String compilationId,
        String candidateRunId, String kind, String targetState, boolean archiveWhenComplete,
        String reasonCode, String parentAction, String state,
        long anchorDesignerVersion, String anchorRequirementRevisionId,
        Integer anchorDiscussionRevision, String readyAt, String completedAt,
        String lastErrorCode, String lastErrorDetail,
        String createdAt, String updatedAt, long version) {
    @AutomapConstructor public AcceptanceCandidateInternalTerminationIntentRow { }
}
