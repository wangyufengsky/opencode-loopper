package io.opencode.loopper.service;

import io.opencode.loopper.domain.AcceptanceCandidateInitialPromptFailureReason;
import io.opencode.loopper.domain.AcceptanceCandidateInternalLaunchState;
import io.opencode.loopper.domain.AcceptanceCandidateInternalParentAction;
import io.opencode.loopper.domain.AcceptanceCandidateInternalTerminationIntentKind;
import io.opencode.loopper.domain.AcceptanceCandidateInternalTerminationIntentState;
import io.opencode.loopper.domain.AcceptanceCandidateInternalTerminationTarget;
import io.opencode.loopper.persistence.AcceptanceCandidateInternalLaunchRow;
import io.opencode.loopper.persistence.AcceptanceCandidateInternalTerminationIntentRow;
import io.opencode.loopper.persistence.DesignRequirementRevisionRow;
import io.opencode.loopper.persistence.DesignerSessionRow;
import io.opencode.loopper.persistence.LoopperMapper;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Freezes an evidence-backed INITIAL prompt failure intent without performing remote I/O. */
@Service
final class AcceptanceCandidateInitialPromptFailureIntentPreparer {
    private final AcceptanceCandidateInternalLaunchStore launches;
    private final AcceptanceCandidateInternalTerminationIntentStore intents;
    private final LoopperMapper mapper;

    AcceptanceCandidateInitialPromptFailureIntentPreparer(
            AcceptanceCandidateInternalLaunchStore launches,
            AcceptanceCandidateInternalTerminationIntentStore intents,
            LoopperMapper mapper) {
        this.launches = launches;
        this.intents = intents;
        this.mapper = mapper;
    }

    AcceptanceCandidateInternalTerminationIntentRow prepare(PrepareCommand command) {
        if (command == null || blank(command.compilationId()) || command.reason() == null) {
            throw new IllegalArgumentException("Compilation and INITIAL prompt failure reason are required");
        }
        Optional<AcceptanceCandidateInternalTerminationIntentRow> active =
                intents.findActiveForCompilation(command.compilationId());
        if (active.isPresent()) return requireReplay(active.get(), command.reason());

        AcceptanceCandidateInternalLaunchRow launch = launches.findForCompilation(command.compilationId())
                .orElseThrow(AcceptanceCandidateInitialPromptFailureIntentPreparer::stale);
        if (!AcceptanceCandidateInternalLaunchState.SETTLED.name().equals(launch.state())) throw stale();
        DesignerSessionRow designer = mapper.findDesignerSession(launch.designerSessionId())
                .orElseThrow(AcceptanceCandidateInitialPromptFailureIntentPreparer::stale);
        DesignRequirementRevisionRow revision = mapper.findCurrentDesignRequirementRevision(designer.id())
                .orElseThrow(AcceptanceCandidateInitialPromptFailureIntentPreparer::stale);
        String at = Instant.now().toString();
        AcceptanceCandidateInternalTerminationIntentRow requested =
                new AcceptanceCandidateInternalTerminationIntentRow(
                        intentId(launch.id()), launch.id(), launch.designerSessionId(), launch.compilationId(),
                        launch.candidateRunId(),
                        AcceptanceCandidateInternalTerminationIntentKind.INITIAL_PROMPT_FAILURE.name(),
                        AcceptanceCandidateInternalTerminationTarget.FAILED_STOPPED.name(), false,
                        command.reason().name(), AcceptanceCandidateInternalParentAction.NONE.name(),
                        AcceptanceCandidateInternalTerminationIntentState.REQUESTED.name(),
                        designer.version(), revision.id(), designer.discussionRevision(), null, null,
                        null, null, at, at, 0);
        return intents.createIdempotent(requested);
    }

    private static AcceptanceCandidateInternalTerminationIntentRow requireReplay(
            AcceptanceCandidateInternalTerminationIntentRow existing,
            AcceptanceCandidateInitialPromptFailureReason reason) {
        if (!AcceptanceCandidateInternalTerminationIntentKind.INITIAL_PROMPT_FAILURE.name()
                    .equals(existing.kind())
                || !AcceptanceCandidateInternalTerminationTarget.FAILED_STOPPED.name()
                    .equals(existing.targetState())
                || existing.archiveWhenComplete()
                || !AcceptanceCandidateInternalParentAction.NONE.name().equals(existing.parentAction())
                || !reason.name().equals(existing.reasonCode())) throw stale();
        return existing;
    }

    static String intentId(String launchId) {
        if (blank(launchId)) throw new IllegalArgumentException("Internal launch id is required");
        return UUID.nameUUIDFromBytes(("acceptance-v7-initial-prompt-failure\n" + launchId)
                .getBytes(StandardCharsets.UTF_8)).toString();
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }

    private static ConflictException stale() {
        return new ConflictException("ACCEPTANCE_INITIAL_PROMPT_FAILURE_STALE",
                "INITIAL 提示失败证据、launch 或 owner 已变化");
    }

    record PrepareCommand(String compilationId, AcceptanceCandidateInitialPromptFailureReason reason) { }
}
