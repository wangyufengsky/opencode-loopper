package io.opencode.loopper.service;

import io.opencode.loopper.domain.DesignRequirementRevisionState;
import io.opencode.loopper.domain.DesignWorkflowPhase;
import io.opencode.loopper.persistence.DesignRequirementRevisionRow;
import io.opencode.loopper.persistence.DesignerSessionRow;
import io.opencode.loopper.persistence.LoopDraftRow;
import io.opencode.loopper.persistence.LoopperMapper;
import java.util.List;

/** Distinguishes a server-produced aggregate checkpoint from an external draft edit. */
final class DesignerRequirementDraftGuard {
    private final LoopperMapper mapper;
    private final LoopDraftService drafts;

    DesignerRequirementDraftGuard(LoopperMapper mapper, LoopDraftService drafts) {
        this.mapper = mapper;
        this.drafts = drafts;
    }

    void requireUnchanged(DesignerSessionRow session, long expectedVersion) {
        if (drafts.get(session.loopDraftId()).version() != expectedVersion) throw changed();
    }

    long retryVersion(DesignerSessionRow session, DesignRequirementRevisionRow revision,
                      boolean allowAggregateRecovery) {
        LoopDraftRow draft = drafts.get(session.loopDraftId());
        if (draft.version() == revision.sourceDraftVersion()) return draft.version();
        boolean recoverableState = DesignRequirementRevisionState.COMPLETED.name().equals(revision.state())
                || DesignRequirementRevisionState.ACTIVE.name().equals(revision.state())
                && DesignWorkflowPhase.REVIEWING_PACKAGE.name().equals(session.workflowPhase());
        if (!allowAggregateRecovery || !recoverableState
                || draft.version() != revision.sourceDraftVersion() + 1
                || !isLegacyUncheckpointedAggregate(revision)
                || !hasExactAggregateMapping(draft, revision)) throw changed();
        return draft.version();
    }

    private boolean isLegacyUncheckpointedAggregate(DesignRequirementRevisionRow revision) {
        return mapper.findTaskDecompositionByRevision(revision.id())
                .map(row -> row.sourceDraftVersion() == revision.sourceDraftVersion()).orElse(false);
    }

    private boolean hasExactAggregateMapping(LoopDraftRow draft, DesignRequirementRevisionRow revision) {
        List<String> expected = mapper.listDesignWorkPackages(revision.id()).stream()
                .map(row -> row.packageId()).toList();
        List<String> actual = drafts.spec(draft).stages().stream().map(stage -> stage.workPackageId())
                .filter(id -> id != null && !id.isBlank()).distinct().toList();
        return !expected.isEmpty() && actual.equals(expected);
    }

    private ConflictException changed() {
        return new ConflictException("DESIGNER_DRAFT_CHANGED",
                "The bound draft changed after the complete requirement revision was frozen");
    }
}
