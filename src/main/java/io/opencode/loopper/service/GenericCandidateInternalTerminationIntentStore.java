package io.opencode.loopper.service;

import io.opencode.loopper.domain.GenericCandidateInternalTerminationIntentState;
import io.opencode.loopper.domain.LifecycleEvent;
import io.opencode.loopper.domain.LifecycleMachineType;
import io.opencode.loopper.domain.LifecycleScopeType;
import io.opencode.loopper.lifecycle.LifecycleTransitionService;
import io.opencode.loopper.persistence.GenericCandidateInternalLaunchRow;
import io.opencode.loopper.persistence.GenericCandidateInternalTerminationIntentRow;
import io.opencode.loopper.persistence.LoopperGenericCandidateTerminationMapper;
import io.opencode.loopper.persistence.LoopperMapper;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Transactional store for V57 external termination intents. */
@Service
class GenericCandidateInternalTerminationIntentStore {
    private final LoopperGenericCandidateTerminationMapper mapper;
    private final LoopperMapper aggregate;
    private final LifecycleTransitionService lifecycle;

    GenericCandidateInternalTerminationIntentStore(
            @Qualifier("loopperGenericCandidateTerminationMapper")
            LoopperGenericCandidateTerminationMapper mapper,
            LoopperMapper aggregate, LifecycleTransitionService lifecycle) {
        this.mapper = mapper;
        this.aggregate = aggregate;
        this.lifecycle = lifecycle;
    }

    Optional<GenericCandidateInternalTerminationIntentRow> findForLaunch(String launchId) {
        return mapper.findGenericCandidateInternalTerminationIntentForLaunch(launchId);
    }
    GenericCandidateInternalTerminationIntentRow require(String id) {
        return mapper.findGenericCandidateInternalTerminationIntent(id)
                .orElseThrow(GenericCandidateInternalTerminationIntentStore::conflict);
    }
    List<GenericCandidateInternalTerminationIntentRow> recoverable() {
        return mapper.listRecoverableGenericCandidateInternalTerminationIntents();
    }
    boolean ownsExternalSession(String remoteId) {
        return mapper.existsGenericCandidateInternalTrackedExternalSession(remoteId);
    }

    @Transactional
    GenericCandidateInternalTerminationIntentRow requestOwnerCancel(
            GenericCandidateInternalTerminationIntentRow row, boolean archiveWhenComplete) {
        GenericCandidateInternalTerminationIntentRow current = require(row.id());
        if (current.ownerCancelRequested() && (!archiveWhenComplete || current.archiveWhenComplete())
                || GenericCandidateInternalTerminationIntentState.COMPLETED.name().equals(current.state())) {
            return current;
        }
        String at = Instant.now().toString();
        lifecycle.mutateWithoutTransition(
                () -> mapper.requestGenericCandidateInternalOwnerCancel(
                        current.id(), current.version(), archiveWhenComplete, at),
                GenericCandidateInternalTerminationIntentStore::conflict);
        return require(current.id());
    }

    @Transactional
    GenericCandidateInternalTerminationIntentRow createIdempotent(
            GenericCandidateInternalTerminationIntentRow requested) {
        Optional<GenericCandidateInternalTerminationIntentRow> existing = findForLaunch(requested.launchId());
        if (existing.isPresent()) return requireSame(existing.get(), requested);
        lifecycle.create(subject(requested), requested.state(), Map.of("kind", requested.intentKind()),
                () -> mapper.insertGenericCandidateInternalTerminationIntent(requested),
                GenericCandidateInternalTerminationIntentStore::conflict);
        return require(requested.id());
    }

    @Transactional
    GenericCandidateInternalTerminationIntentRow recover(
            GenericCandidateInternalTerminationIntentRow row) {
        if (!GenericCandidateInternalTerminationIntentState.DISCONNECTED.name().equals(row.state())) return row;
        return transition(row, copy(row, GenericCandidateInternalTerminationIntentState.REQUESTED,
                null, null, null, null), LifecycleEvent.RECOVER,
                "GENERIC_CANDIDATE_INTERNAL_TERMINATION_RECOVERED");
    }

    @Transactional
    GenericCandidateInternalTerminationIntentRow disconnected(
            GenericCandidateInternalTerminationIntentRow row, String code, String detail) {
        GenericCandidateInternalTerminationIntentRow next = copy(row,
                GenericCandidateInternalTerminationIntentState.DISCONNECTED,
                null, null, safe(code), safe(detail));
        if (GenericCandidateInternalTerminationIntentState.DISCONNECTED.name().equals(row.state())) {
            lifecycle.mutateWithoutTransition(
                    () -> mapper.updateGenericCandidateInternalTerminationIntent(next),
                    GenericCandidateInternalTerminationIntentStore::conflict);
            return require(row.id());
        }
        return transition(row, next, LifecycleEvent.DISCONNECT,
                "GENERIC_CANDIDATE_INTERNAL_TERMINATION_STOP_UNKNOWN");
    }

    @Transactional
    GenericCandidateInternalTerminationIntentRow ready(
            GenericCandidateInternalTerminationIntentRow row) {
        if (GenericCandidateInternalTerminationIntentState.READY.name().equals(row.state())) return row;
        return transition(row, copy(row, GenericCandidateInternalTerminationIntentState.READY,
                Instant.now().toString(), null, null, null), LifecycleEvent.COMPLETE,
                "GENERIC_CANDIDATE_INTERNAL_TERMINATION_READY");
    }

    @Transactional
    GenericCandidateInternalTerminationIntentRow complete(
            GenericCandidateInternalTerminationIntentRow row) {
        if (!GenericCandidateInternalTerminationIntentState.READY.name().equals(row.state())) throw conflict();
        String at = Instant.now().toString();
        lifecycle.transition(subject(row), row.state(),
                GenericCandidateInternalTerminationIntentState.COMPLETED.name(), LifecycleEvent.FINISH,
                "GENERIC_CANDIDATE_INTERNAL_TERMINATION_COMPLETED", Map.of(),
                () -> mapper.completeGenericCandidateInternalTerminationIntent(
                        row.id(), row.version(), at, at),
                GenericCandidateInternalTerminationIntentStore::conflict);
        return require(row.id());
    }

    private GenericCandidateInternalTerminationIntentRow transition(
            GenericCandidateInternalTerminationIntentRow current,
            GenericCandidateInternalTerminationIntentRow next,
            LifecycleEvent event, String reason) {
        lifecycle.transition(subject(current), current.state(), next.state(), event, reason, Map.of(),
                () -> mapper.updateGenericCandidateInternalTerminationIntent(next),
                GenericCandidateInternalTerminationIntentStore::conflict);
        return require(current.id());
    }

    private LifecycleTransitionService.Subject subject(GenericCandidateInternalTerminationIntentRow row) {
        GenericCandidateInternalLaunchRow launch = aggregate.findGenericCandidateInternalLaunch(row.launchId())
                .orElseThrow(GenericCandidateInternalTerminationIntentStore::conflict);
        LifecycleScopeType type = launch.designerSessionId() != null ? LifecycleScopeType.DESIGNER
                : launch.taskId() != null ? LifecycleScopeType.TASK : LifecycleScopeType.PROJECT;
        String scope = launch.designerSessionId() != null ? launch.designerSessionId()
                : launch.taskId() != null ? launch.taskId() : launch.projectId();
        return new LifecycleTransitionService.Subject(
                LifecycleMachineType.GENERIC_CANDIDATE_INTERNAL_TERMINATION_INTENT,
                row.id(), type, scope);
    }

    private static GenericCandidateInternalTerminationIntentRow copy(
            GenericCandidateInternalTerminationIntentRow row,
            GenericCandidateInternalTerminationIntentState state,
            String readyAt, String completedAt, String code, String detail) {
        return new GenericCandidateInternalTerminationIntentRow(
                row.id(), row.launchId(), row.candidateRunId(), row.intentKind(), row.targetLaunchState(),
                state.name(), row.reasonCode(), row.ownerCancelRequested(), row.archiveWhenComplete(),
                row.anchorOwnerVersion(),
                readyAt == null ? row.readyAt() : readyAt,
                completedAt == null ? row.completedAt() : completedAt,
                code, detail, row.createdAt(), Instant.now().toString(), row.version());
    }

    private static GenericCandidateInternalTerminationIntentRow requireSame(
            GenericCandidateInternalTerminationIntentRow stored,
            GenericCandidateInternalTerminationIntentRow requested) {
        if (!stored.id().equals(requested.id()) || !stored.candidateRunId().equals(requested.candidateRunId())
                || !stored.intentKind().equals(requested.intentKind())
                || !stored.targetLaunchState().equals(requested.targetLaunchState())
                || !java.util.Objects.equals(stored.reasonCode(), requested.reasonCode())
                || stored.ownerCancelRequested() != requested.ownerCancelRequested()
                || stored.archiveWhenComplete() != requested.archiveWhenComplete()
                || stored.anchorOwnerVersion() != requested.anchorOwnerVersion()) throw conflict();
        return stored;
    }
    private static String safe(String value) {
        if (value == null) return null;
        return value.length() <= 2_000 ? value : value.substring(0, 2_000);
    }
    private static ConflictException conflict() {
        return new ConflictException("GENERIC_CANDIDATE_INTERNAL_TERMINATION_CONFLICT",
                "通用候选 termination intent 已变化或不满足收束条件");
    }
}
