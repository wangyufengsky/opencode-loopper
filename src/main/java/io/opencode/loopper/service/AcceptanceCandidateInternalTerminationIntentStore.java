package io.opencode.loopper.service;

import io.opencode.loopper.domain.AcceptanceCandidateInternalTerminationIntentState;
import io.opencode.loopper.domain.AcceptanceCandidateInternalParentAction;
import io.opencode.loopper.domain.LifecycleEvent;
import io.opencode.loopper.domain.LifecycleMachineType;
import io.opencode.loopper.domain.LifecycleScopeType;
import io.opencode.loopper.lifecycle.LifecycleTransitionService;
import io.opencode.loopper.persistence.AcceptanceCandidateInternalTerminationIntentRow;
import io.opencode.loopper.persistence.AcceptanceCandidateInternalLaunchRow;
import io.opencode.loopper.persistence.LoopperAcceptanceCandidateTerminationMapper;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

/** Small transactional seam over the durable external termination-intent ledger. */
@Service
final class AcceptanceCandidateInternalTerminationIntentStore {
    private final LoopperAcceptanceCandidateTerminationMapper mapper;
    private final LifecycleTransitionService lifecycle;

    AcceptanceCandidateInternalTerminationIntentStore(
            @Qualifier("loopperAcceptanceCandidateTerminationMapper")
            LoopperAcceptanceCandidateTerminationMapper mapper,
            LifecycleTransitionService lifecycle) {
        this.mapper = mapper;
        this.lifecycle = lifecycle;
    }

    AcceptanceCandidateInternalTerminationIntentRow create(
            AcceptanceCandidateInternalTerminationIntentRow row) {
        requireState(row);
        lifecycle.create(subject(row), row.state(), Map.of("kind", row.kind(), "targetState", row.targetState()),
                () -> mapper.insertAcceptanceCandidateInternalTerminationIntent(row),
                AcceptanceCandidateInternalTerminationIntentStore::conflict);
        return require(row.id());
    }

    Optional<AcceptanceCandidateInternalTerminationIntentRow> findForLaunch(String launchId) {
        return mapper.findAcceptanceCandidateInternalTerminationIntentForLaunch(launchId);
    }

    Optional<AcceptanceCandidateInternalTerminationIntentRow> findActiveForCompilation(String compilationId) {
        return mapper.findActiveAcceptanceCandidateInternalTerminationIntentForCompilation(compilationId);
    }

    AcceptanceCandidateInternalTerminationIntentRow createIdempotent(
            AcceptanceCandidateInternalTerminationIntentRow row) {
        Optional<AcceptanceCandidateInternalTerminationIntentRow> existing = findActiveForCompilation(
                row.compilationId());
        if (existing.isPresent()) return requireSameIntent(existing.get(), row);
        try {
            return create(row);
        } catch (RuntimeException raced) {
            return findActiveForCompilation(row.compilationId())
                    .map(value -> requireSameIntent(value, row))
                    .orElseThrow(() -> raced);
        }
    }

    AcceptanceCandidateInternalTerminationIntentRow requireIntent(String id) { return require(id); }

    AcceptanceCandidateInternalTerminationIntentRow recover(
            AcceptanceCandidateInternalTerminationIntentRow row) {
        if (!AcceptanceCandidateInternalTerminationIntentState.DISCONNECTED.name().equals(row.state())) return row;
        return transition(row, copy(row, AcceptanceCandidateInternalTerminationIntentState.REQUESTED,
                null, null, null, null), LifecycleEvent.RECOVER,
                "ACCEPTANCE_INTERNAL_TERMINATION_RECOVERED");
    }

    AcceptanceCandidateInternalTerminationIntentRow disconnected(
            AcceptanceCandidateInternalTerminationIntentRow row, String code, String detail) {
        if (AcceptanceCandidateInternalTerminationIntentState.DISCONNECTED.name().equals(row.state())) {
            return mutate(copy(row, AcceptanceCandidateInternalTerminationIntentState.DISCONNECTED,
                    null, null, code, safe(detail)));
        }
        return transition(row, copy(row, AcceptanceCandidateInternalTerminationIntentState.DISCONNECTED,
                null, null, code, safe(detail)), LifecycleEvent.DISCONNECT,
                "ACCEPTANCE_INTERNAL_TERMINATION_STOP_UNCONFIRMED");
    }

    AcceptanceCandidateInternalTerminationIntentRow ready(
            AcceptanceCandidateInternalTerminationIntentRow row) {
        if (AcceptanceCandidateInternalTerminationIntentState.READY.name().equals(row.state())) return row;
        return transition(row, copy(row, AcceptanceCandidateInternalTerminationIntentState.READY,
                Instant.now().toString(), null, null, null), LifecycleEvent.COMPLETE,
                "ACCEPTANCE_INTERNAL_TERMINATION_READY");
    }

    List<AcceptanceCandidateInternalTerminationIntentRow> activeForDesigner(String designerSessionId) {
        return mapper.listActiveAcceptanceCandidateInternalTerminationIntents(designerSessionId);
    }

    List<AcceptanceCandidateInternalTerminationIntentRow> recoverable() {
        return mapper.listRecoverableAcceptanceCandidateInternalTerminationIntents();
    }

    boolean hasActiveForDesigner(String designerSessionId) {
        return mapper.existsActiveAcceptanceCandidateInternalTerminationIntentForDesigner(designerSessionId);
    }

    List<AcceptanceCandidateInternalLaunchRow> terminableLaunchesForDesigner(String designerSessionId) {
        return mapper.listTerminableAcceptanceCandidateInternalLaunchesForDesigner(designerSessionId);
    }

    boolean ownsExternalSession(String externalSessionId) {
        return mapper.existsAcceptanceCandidateInternalTrackedExternalSession(externalSessionId);
    }

    AcceptanceCandidateInternalTerminationIntentRow promoteInitialParentAction(
            AcceptanceCandidateInternalTerminationIntentRow row,
            AcceptanceCandidateInternalParentAction action, boolean archiveWhenComplete) {
        if (row == null || action == null || action == AcceptanceCandidateInternalParentAction.NONE
                || !"INITIAL_PROMPT_FAILURE".equals(row.kind())) throw conflict();
        if (action == AcceptanceCandidateInternalParentAction.OWNER_REPLACEMENT && archiveWhenComplete) {
            throw conflict();
        }
        if (action.name().equals(row.parentAction())) {
            if (row.archiveWhenComplete() != archiveWhenComplete) throw conflict();
            return row;
        }
        String at = Instant.now().toString();
        lifecycle.mutateWithoutTransition(
                () -> mapper.promoteAcceptanceCandidateInitialTerminationParentAction(
                        row.id(), row.version(), action.name(), archiveWhenComplete, at),
                AcceptanceCandidateInternalTerminationIntentStore::conflict);
        return require(row.id());
    }

    AcceptanceCandidateInternalTerminationIntentRow complete(
            AcceptanceCandidateInternalTerminationIntentRow row) {
        if (!AcceptanceCandidateInternalTerminationIntentState.READY.name().equals(row.state())) throw conflict();
        String completedAt = Instant.now().toString();
        lifecycle.transition(subject(row), row.state(),
                AcceptanceCandidateInternalTerminationIntentState.COMPLETED.name(), LifecycleEvent.FINISH,
                "ACCEPTANCE_INTERNAL_TERMINATION_COMPLETED", Map.of(),
                () -> mapper.completeAcceptanceCandidateInternalTerminationIntent(
                        row.id(), row.version(), completedAt, completedAt),
                AcceptanceCandidateInternalTerminationIntentStore::conflict);
        return require(row.id());
    }

    AcceptanceCandidateInternalTerminationIntentRow transition(
            AcceptanceCandidateInternalTerminationIntentRow current,
            AcceptanceCandidateInternalTerminationIntentRow next,
            LifecycleEvent event, String reasonCode) {
        if (!current.id().equals(next.id()) || current.version() != next.version()) throw conflict();
        requireState(current);
        requireState(next);
        lifecycle.transition(subject(current), current.state(), next.state(), event, reasonCode, Map.of(),
                () -> mapper.updateAcceptanceCandidateInternalTerminationIntent(next),
                AcceptanceCandidateInternalTerminationIntentStore::conflict);
        return require(current.id());
    }

    AcceptanceCandidateInternalTerminationIntentRow mutate(
            AcceptanceCandidateInternalTerminationIntentRow row) {
        requireState(row);
        AcceptanceCandidateInternalTerminationIntentRow current = require(row.id());
        if (current.version() != row.version() || !current.state().equals(row.state())) throw conflict();
        lifecycle.mutateWithoutTransition(
                () -> mapper.updateAcceptanceCandidateInternalTerminationIntent(row),
                AcceptanceCandidateInternalTerminationIntentStore::conflict);
        return require(row.id());
    }

    private AcceptanceCandidateInternalTerminationIntentRow require(String id) {
        return mapper.findAcceptanceCandidateInternalTerminationIntent(id).orElseThrow(
                AcceptanceCandidateInternalTerminationIntentStore::conflict);
    }

    private static void requireState(AcceptanceCandidateInternalTerminationIntentRow row) {
        try { AcceptanceCandidateInternalTerminationIntentState.valueOf(row.state()); }
        catch (RuntimeException invalid) { throw conflict(); }
    }

    private static AcceptanceCandidateInternalTerminationIntentRow copy(
            AcceptanceCandidateInternalTerminationIntentRow row,
            AcceptanceCandidateInternalTerminationIntentState state,
            String readyAt, String completedAt, String code, String detail) {
        return new AcceptanceCandidateInternalTerminationIntentRow(
                row.id(), row.launchId(), row.designerSessionId(), row.compilationId(), row.candidateRunId(),
                row.kind(), row.targetState(), row.archiveWhenComplete(), row.reasonCode(), row.parentAction(),
                state.name(),
                row.anchorDesignerVersion(),
                row.anchorRequirementRevisionId(), row.anchorDiscussionRevision(), readyAt, completedAt,
                code, detail, row.createdAt(), Instant.now().toString(), row.version());
    }

    private static AcceptanceCandidateInternalTerminationIntentRow requireSameIntent(
            AcceptanceCandidateInternalTerminationIntentRow stored,
            AcceptanceCandidateInternalTerminationIntentRow requested) {
        if (!stored.launchId().equals(requested.launchId())
                || !stored.designerSessionId().equals(requested.designerSessionId())
                || !stored.compilationId().equals(requested.compilationId())
                || !stored.candidateRunId().equals(requested.candidateRunId())
                || !stored.kind().equals(requested.kind())
                || !stored.targetState().equals(requested.targetState())
                || stored.archiveWhenComplete() != requested.archiveWhenComplete()
                || !java.util.Objects.equals(stored.reasonCode(), requested.reasonCode())
                || !stored.parentAction().equals(requested.parentAction())
                || stored.anchorDesignerVersion() != requested.anchorDesignerVersion()
                || !java.util.Objects.equals(stored.anchorRequirementRevisionId(),
                        requested.anchorRequirementRevisionId())
                || !java.util.Objects.equals(stored.anchorDiscussionRevision(),
                        requested.anchorDiscussionRevision())) throw conflict();
        return stored;
    }

    private static String safe(String value) {
        if (value == null) return null;
        return value.length() <= 2_000 ? value : value.substring(0, 2_000);
    }

    private static LifecycleTransitionService.Subject subject(
            AcceptanceCandidateInternalTerminationIntentRow row) {
        return new LifecycleTransitionService.Subject(
                LifecycleMachineType.ACCEPTANCE_CANDIDATE_INTERNAL_TERMINATION_INTENT,
                row.id(), LifecycleScopeType.DESIGNER, row.designerSessionId());
    }

    private static ConflictException conflict() {
        return new ConflictException("ACCEPTANCE_INTERNAL_TERMINATION_CONFLICT",
                "验收候选内部终止意图已变化或不满足收束条件");
    }
}
