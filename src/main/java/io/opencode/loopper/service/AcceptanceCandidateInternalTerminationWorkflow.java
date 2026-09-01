package io.opencode.loopper.service;

import io.opencode.loopper.domain.AcceptanceCandidateInitialPromptFailureReason;
import io.opencode.loopper.domain.AcceptanceCandidateInternalParentAction;
import io.opencode.loopper.domain.AcceptanceCandidateInternalTerminationIntentKind;
import io.opencode.loopper.domain.AcceptanceCandidateInternalTerminationIntentState;
import io.opencode.loopper.domain.AcceptanceCandidateInternalTerminationTarget;
import io.opencode.loopper.persistence.AcceptanceCandidateInternalLaunchRow;
import io.opencode.loopper.persistence.AcceptanceCandidateInternalTerminationIntentRow;
import io.opencode.loopper.persistence.DesignRequirementRevisionRow;
import io.opencode.loopper.persistence.DesignerSessionRow;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** One durable entry point for every external stop of an Acceptance INTERNAL_MCP writer. */
@Service
final class AcceptanceCandidateInternalTerminationWorkflow {
    private final AcceptanceCandidateInternalTerminationIntentStore intents;
    private final AcceptanceCandidateInternalTerminationCoordinator coordinator;
    private final AcceptanceCandidateInitialPromptFailureIntentPreparer initialFailures;

    AcceptanceCandidateInternalTerminationWorkflow(
            AcceptanceCandidateInternalTerminationIntentStore intents,
            AcceptanceCandidateInternalTerminationCoordinator coordinator,
            AcceptanceCandidateInitialPromptFailureIntentPreparer initialFailures) {
        this.intents = intents;
        this.coordinator = coordinator;
        this.initialFailures = initialFailures;
    }

    Batch requestDesignerCancellation(DesignerSessionRow designer, boolean archiveWhenComplete) {
        if (designer == null || !"STOPPING".equals(designer.state())) throw stale();
        promoteActive(designer.id(), AcceptanceCandidateInternalParentAction.DESIGNER_CANCEL,
                archiveWhenComplete);
        for (AcceptanceCandidateInternalLaunchRow launch : intents.terminableLaunchesForDesigner(designer.id())) {
            ensureIntent(launch, designer, null, AcceptanceCandidateInternalParentAction.DESIGNER_CANCEL,
                    archiveWhenComplete);
        }
        return advanceDesigner(designer.id());
    }

    Batch requestOwnerReplacement(DesignerSessionRow designer, DesignRequirementRevisionRow revision) {
        if (designer == null || revision == null || !designer.id().equals(revision.designerSessionId())
                || designer.currentRequirementRevision() == null
                || designer.currentRequirementRevision() != revision.revision()
                || "STOPPING".equals(designer.state()) || "CANCELLED".equals(designer.state())) throw stale();
        promoteActive(designer.id(), AcceptanceCandidateInternalParentAction.OWNER_REPLACEMENT, false);
        for (AcceptanceCandidateInternalLaunchRow launch : intents.terminableLaunchesForDesigner(designer.id())) {
            ensureIntent(launch, designer, revision, AcceptanceCandidateInternalParentAction.OWNER_REPLACEMENT,
                    false);
        }
        return advanceDesigner(designer.id());
    }

    Optional<AcceptanceCandidateInternalTerminationCoordinator.Result> requestInitialPromptFailure(
            String compilationId, AcceptanceCandidateInitialPromptFailureReason reason) {
        try {
            AcceptanceCandidateInternalTerminationIntentRow intent = initialFailures.prepare(
                    new AcceptanceCandidateInitialPromptFailureIntentPreparer.PrepareCommand(compilationId, reason));
            return Optional.of(coordinator.advance(intent.id()));
        } catch (ConflictException raced) {
            return intents.findActiveForCompilation(compilationId).map(row -> coordinator.advance(row.id()));
        }
    }

    Optional<AcceptanceCandidateInternalTerminationCoordinator.Result> advanceInitialFailure(
            String compilationId) {
        return intents.findActiveForCompilation(compilationId)
                .filter(row -> "INITIAL_PROMPT_FAILURE".equals(row.kind()))
                .filter(row -> AcceptanceCandidateInternalParentAction.NONE.name().equals(row.parentAction()))
                .map(row -> coordinator.advance(row.id()));
    }

    Batch advanceDesigner(String designerSessionId) {
        List<AcceptanceCandidateInternalTerminationCoordinator.Result> results = new ArrayList<>();
        for (AcceptanceCandidateInternalTerminationIntentRow row : intents.activeForDesigner(designerSessionId)) {
            results.add(coordinator.advance(row.id()));
        }
        return new Batch(List.copyOf(results));
    }

    void advanceRecoverable() {
        for (AcceptanceCandidateInternalTerminationIntentRow row : intents.recoverable()) {
            try { coordinator.advance(row.id()); }
            catch (RuntimeException ignoredConcurrentRecovery) { }
        }
    }

    boolean hasActive(String designerSessionId) { return intents.hasActiveForDesigner(designerSessionId); }
    boolean ownsExternalSession(String externalSessionId) { return intents.ownsExternalSession(externalSessionId); }

    boolean archiveRequested(String designerSessionId) {
        return intents.activeForDesigner(designerSessionId).stream()
                .filter(row -> AcceptanceCandidateInternalParentAction.DESIGNER_CANCEL.name()
                        .equals(row.parentAction()))
                .anyMatch(AcceptanceCandidateInternalTerminationIntentRow::archiveWhenComplete);
    }

    void completeReadyParentActionInCurrentTransaction(
            String designerSessionId, AcceptanceCandidateInternalParentAction action) {
        List<AcceptanceCandidateInternalTerminationIntentRow> rows = intents.activeForDesigner(designerSessionId)
                .stream().filter(row -> action.name().equals(row.parentAction())).toList();
        if (rows.stream().anyMatch(row -> !AcceptanceCandidateInternalTerminationIntentState.READY.name()
                .equals(row.state()))) throw stale();
        rows.forEach(intents::complete);
    }

    void completeReadyInitialFailureInCurrentTransaction(String intentId) {
        AcceptanceCandidateInternalTerminationIntentRow row = intents.requireIntent(intentId);
        if (!AcceptanceCandidateInternalTerminationIntentKind.INITIAL_PROMPT_FAILURE.name().equals(row.kind())
                || !AcceptanceCandidateInternalParentAction.NONE.name().equals(row.parentAction())
                || !AcceptanceCandidateInternalTerminationIntentState.READY.name().equals(row.state())) {
            throw stale();
        }
        intents.complete(row);
    }

    List<AcceptanceCandidateInternalTerminationIntentRow> active(String designerSessionId) {
        return intents.activeForDesigner(designerSessionId);
    }

    Set<String> activeParentActionDesigners(AcceptanceCandidateInternalParentAction action) {
        return intents.recoverable().stream().filter(row -> action.name().equals(row.parentAction()))
                .map(AcceptanceCandidateInternalTerminationIntentRow::designerSessionId)
                .collect(Collectors.toCollection(java.util.LinkedHashSet::new));
    }

    Set<String> activeInitialFailureCompilations() {
        return intents.recoverable().stream()
                .filter(row -> AcceptanceCandidateInternalParentAction.NONE.name().equals(row.parentAction()))
                .filter(row -> AcceptanceCandidateInternalTerminationIntentKind.INITIAL_PROMPT_FAILURE.name()
                        .equals(row.kind()))
                .map(AcceptanceCandidateInternalTerminationIntentRow::compilationId)
                .collect(Collectors.toCollection(java.util.LinkedHashSet::new));
    }

    private void promoteActive(String designerSessionId, AcceptanceCandidateInternalParentAction action,
            boolean archiveWhenComplete) {
        for (AcceptanceCandidateInternalTerminationIntentRow row : intents.activeForDesigner(designerSessionId)) {
            if (action.name().equals(row.parentAction())) {
                if (row.archiveWhenComplete() != archiveWhenComplete
                        && !(action == AcceptanceCandidateInternalParentAction.DESIGNER_CANCEL
                        && row.archiveWhenComplete())) throw stale();
            } else if ("INITIAL_PROMPT_FAILURE".equals(row.kind())
                    && AcceptanceCandidateInternalParentAction.NONE.name().equals(row.parentAction())) {
                intents.promoteInitialParentAction(row, action, archiveWhenComplete);
            } else {
                throw stale();
            }
        }
    }

    private AcceptanceCandidateInternalTerminationIntentRow ensureIntent(
            AcceptanceCandidateInternalLaunchRow launch, DesignerSessionRow designer,
            DesignRequirementRevisionRow revision, AcceptanceCandidateInternalParentAction action,
            boolean archiveWhenComplete) {
        Optional<AcceptanceCandidateInternalTerminationIntentRow> active =
                intents.findActiveForCompilation(launch.compilationId());
        if (active.isPresent()) return active.orElseThrow();
        boolean cancellation = action == AcceptanceCandidateInternalParentAction.DESIGNER_CANCEL;
        String at = Instant.now().toString();
        AcceptanceCandidateInternalTerminationIntentRow row =
                new AcceptanceCandidateInternalTerminationIntentRow(
                        intentId(action, launch.id()), launch.id(), designer.id(), launch.compilationId(),
                        launch.candidateRunId(), cancellation
                        ? AcceptanceCandidateInternalTerminationIntentKind.DESIGNER_CANCEL.name()
                        : AcceptanceCandidateInternalTerminationIntentKind.OWNER_REPLACEMENT.name(),
                        cancellation ? AcceptanceCandidateInternalTerminationTarget.CANCELLED.name()
                                : AcceptanceCandidateInternalTerminationTarget.STALE.name(),
                        archiveWhenComplete, null, action.name(),
                        AcceptanceCandidateInternalTerminationIntentState.REQUESTED.name(), designer.version(),
                        revision == null ? null : revision.id(),
                        revision == null ? null : designer.discussionRevision(), null, null,
                        null, null, at, at, 0);
        return intents.createIdempotent(row);
    }

    private static String intentId(AcceptanceCandidateInternalParentAction action, String launchId) {
        return UUID.nameUUIDFromBytes(("acceptance-v7-termination\n" + action.name() + "\n" + launchId)
                .getBytes(StandardCharsets.UTF_8)).toString();
    }

    private static ConflictException stale() {
        return new ConflictException("ACCEPTANCE_INTERNAL_TERMINATION_STALE",
                "验收候选内部终止命令、父级动作或 owner 已变化");
    }

    record Batch(List<AcceptanceCandidateInternalTerminationCoordinator.Result> results) {
        Batch { results = results == null ? List.of() : List.copyOf(results); }
        boolean ready() { return results.stream().allMatch(result ->
                result.status() == AcceptanceCandidateInternalTerminationCoordinator.Status.READY); }
        int stoppedSessions() { return (int) results.stream().filter(result -> result.status()
                == AcceptanceCandidateInternalTerminationCoordinator.Status.READY).count(); }
        int failedSessions() { return (int) results.stream().filter(result -> result.status()
                == AcceptanceCandidateInternalTerminationCoordinator.Status.DISCONNECTED).count(); }
    }
}
