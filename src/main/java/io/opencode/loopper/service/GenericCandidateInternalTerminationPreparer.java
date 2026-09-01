package io.opencode.loopper.service;

import io.opencode.loopper.domain.GenericCandidateInternalLaunchState;
import io.opencode.loopper.domain.GenericCandidateInternalTerminationIntentState;
import io.opencode.loopper.persistence.GenericCandidateInternalLaunchRow;
import io.opencode.loopper.persistence.GenericCandidateInternalTerminationIntentRow;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Freezes a typed V57 termination intent without remote I/O or role-owner mutation. */
@Service
final class GenericCandidateInternalTerminationPreparer {
    private final GenericCandidateInternalLaunchService launches;
    private final GenericCandidateInternalTerminationIntentStore intents;

    GenericCandidateInternalTerminationPreparer(
            GenericCandidateInternalLaunchService launches,
            GenericCandidateInternalTerminationIntentStore intents) {
        this.launches = launches;
        this.intents = intents;
    }

    GenericCandidateInternalTerminationIntentRow prepare(PrepareCommand command) {
        if (command == null || blank(command.launchId()) || command.kind() == null
                || blank(command.reasonCode())) {
            throw new IllegalArgumentException("Launch, termination kind, and reason are required");
        }
        GenericCandidateInternalLaunchRow launch = launches.require(command.launchId());
        GenericCandidateInternalLaunchState launchState =
                GenericCandidateInternalLaunchState.valueOf(launch.state());
        if (launchState.terminal()
                || (command.kind() == IntentKind.RUN_COMPLETED
                    && launchState != GenericCandidateInternalLaunchState.SETTLED)) throw stale();
        long anchor = launch.settledOwnerVersion() == null
                ? launch.preparedOwnerVersion() : launch.settledOwnerVersion();
        String at = Instant.now().toString();
        GenericCandidateInternalTerminationIntentRow row =
                new GenericCandidateInternalTerminationIntentRow(
                        intentId(launch.id(), command.kind()), launch.id(), launch.candidateRunId(),
                        command.kind().name(), command.kind().targetState(),
                        GenericCandidateInternalTerminationIntentState.REQUESTED.name(),
                        command.reasonCode(), command.kind() == IntentKind.OWNER_CANCEL, false,
                        anchor, null, null, null, null, at, at, 0);
        return intents.createIdempotent(row);
    }

    static String intentId(String launchId, IntentKind kind) {
        return UUID.nameUUIDFromBytes(("generic-v1-termination\n" + kind + "\n" + launchId)
                .getBytes(StandardCharsets.UTF_8)).toString();
    }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static ConflictException stale() {
        return new ConflictException("GENERIC_CANDIDATE_INTERNAL_TERMINATION_STALE",
                "通用候选 launch 已终态或终止锚点已变化");
    }

    enum IntentKind {
        RUN_COMPLETED("COMPLETED"), OWNER_CANCEL("CANCELLED"),
        OWNER_REPLACEMENT("STALE"), PROTOCOL_FAILURE("FAILED_STOPPED");
        private final String targetState;
        IntentKind(String targetState) { this.targetState = targetState; }
        String targetState() { return targetState; }
    }
    record PrepareCommand(String launchId, IntentKind kind, String reasonCode) { }
}
