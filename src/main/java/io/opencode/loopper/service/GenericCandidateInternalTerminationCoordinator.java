package io.opencode.loopper.service;

import io.opencode.loopper.config.LoopperProperties;
import io.opencode.loopper.domain.GenericCandidateInternalLaunchCleanupState;
import io.opencode.loopper.domain.GenericCandidateInternalLaunchState;
import io.opencode.loopper.domain.GenericCandidateInternalTerminationIntentState;
import io.opencode.loopper.persistence.GenericCandidateInternalLaunchCleanupRemoteRow;
import io.opencode.loopper.persistence.GenericCandidateInternalLaunchRow;
import io.opencode.loopper.persistence.GenericCandidateInternalTerminationIntentRow;
import io.opencode.loopper.runtime.OpenCodeClient;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Reentrant remote-I/O coordinator for one V57 termination intent. */
@Component
final class GenericCandidateInternalTerminationCoordinator {
    private final GenericCandidateInternalTerminationIntentStore intents;
    private final GenericCandidateInternalLaunchService launches;
    private final GenericCandidateInternalLaunchCleanupLedger cleanup;
    private final GenericCandidateInternalTerminationSettlementService settlement;
    private final CandidatePromptDispatchService prompts;
    private final OpenCodeClient openCode;
    private final Duration claimTtl;

    GenericCandidateInternalTerminationCoordinator(
            GenericCandidateInternalTerminationIntentStore intents,
            GenericCandidateInternalLaunchService launches,
            GenericCandidateInternalLaunchCleanupLedger cleanup,
            GenericCandidateInternalTerminationSettlementService settlement,
            CandidatePromptDispatchService prompts, OpenCodeClient openCode, LoopperProperties properties) {
        this.intents = intents; this.launches = launches; this.cleanup = cleanup;
        this.settlement = settlement; this.prompts = prompts; this.openCode = openCode;
        this.claimTtl = AcceptanceCandidateLegacyHandoffCoordinator.claimTtl(properties);
    }

    Result advance(String intentId) {
        GenericCandidateInternalTerminationIntentRow intent = intents.require(intentId);
        GenericCandidateInternalTerminationIntentState intentState =
                GenericCandidateInternalTerminationIntentState.valueOf(intent.state());
        if (intentState == GenericCandidateInternalTerminationIntentState.READY
                || intentState == GenericCandidateInternalTerminationIntentState.COMPLETED) {
            return Result.ready(intent);
        }
        if (intentState == GenericCandidateInternalTerminationIntentState.DISCONNECTED) {
            intent = intents.recover(intent);
        }
        GenericCandidateInternalLaunchRow launch = launches.require(intent.launchId());
        GenericCandidateInternalLaunchState state = GenericCandidateInternalLaunchState.valueOf(launch.state());
        if (state == GenericCandidateInternalLaunchState.PREPARED) {
            return Result.ready(settlement.finishWithoutRemote(intentId));
        }
        if (state.terminal()) return Result.ready(intents.ready(intents.require(intentId)));
        List<GenericCandidateInternalLaunchCleanupRemoteRow> remotes = cleanup.list(launch.id());
        if (state == GenericCandidateInternalLaunchState.SETTLED) {
            if (!prompts.prepareRunTermination(launch.candidateRunId(), Instant.now())) {
                return Result.pending(intents.require(intentId), "CANDIDATE_PROMPT_IO_IN_FLIGHT");
            }
            if (remotes.isEmpty()) remotes = settlement.registerCleanup(intentId, List.of(attestation(launch)));
        } else if (remotes.isEmpty() && launch.externalSessionId() != null) {
            remotes = settlement.registerCleanup(intentId, List.of(attestation(launch)));
        } else if (remotes.isEmpty()) {
            return Result.disconnected(intents.disconnected(intents.require(intentId),
                    "OPENCODE_CREATE_RESULT_UNKNOWN", "No attested remote is available for termination"));
        }
        for (GenericCandidateInternalLaunchCleanupRemoteRow remote : remotes) {
            if (GenericCandidateInternalLaunchCleanupState.STOPPED.name().equals(remote.state())) continue;
            var claim = cleanup.claimStop(launch.id(), remote.externalSessionId(),
                    UUID.randomUUID().toString(), Instant.now(), claimTtl);
            if (!claim.acquired()) return Result.pending(intents.require(intentId), claim.reason());
            String remoteId = remote.externalSessionId();
            remote = cleanup.list(launch.id()).stream()
                    .filter(row -> row.externalSessionId().equals(remoteId))
                    .findFirst().orElseThrow();
            OpenCodeClient.OpenCodeSession session = new OpenCodeClient.OpenCodeSession(
                    remote.externalSessionId(), Path.of(launch.canonicalDirectory()),
                    remote.runtimeGenerationId(), launch.internalMcpServer());
            if (remote.stopDispatchAttempted()) {
                OpenCodeClient.SessionStatus status = openCode.sessionStatus(session);
                if (status == null || !status.completed()) {
                    cleanup.disconnected(launch.id(), remote.externalSessionId(), claim,
                            "OPENCODE_GENERIC_CANDIDATE_STOP_RESULT_UNKNOWN",
                            status == null ? "No status returned" : status.state());
                    return Result.disconnected(intents.disconnected(intents.require(intentId),
                            "OPENCODE_GENERIC_CANDIDATE_STOP_RESULT_UNKNOWN", "Remote stop is unconfirmed"));
                }
                cleanup.stopped(launch.id(), remote.externalSessionId(), claim,
                        CandidateSessionTerminationProof.REMOTE_COMPLETED.name());
                continue;
            }
            var checkpoint = cleanup.markStopDispatchStarted(
                    launch.id(), remote.externalSessionId(), claim);
            if (!checkpoint.newlyStarted()) return Result.pending(intents.require(intentId), "STOP_DISPATCHED");
            try {
                cleanup.stopped(launch.id(), remote.externalSessionId(), claim,
                        CandidateSessionTerminationProof.from(openCode.abortWithConfirmation(session)).name());
            } catch (RuntimeException unknown) {
                cleanup.disconnected(launch.id(), remote.externalSessionId(), claim,
                        "OPENCODE_GENERIC_CANDIDATE_STOP_RESULT_UNKNOWN", unknown.getMessage());
                return Result.disconnected(intents.disconnected(intents.require(intentId),
                        "OPENCODE_GENERIC_CANDIDATE_STOP_RESULT_UNKNOWN", unknown.getMessage()));
            }
        }
        if (state == GenericCandidateInternalLaunchState.SETTLED) {
            String proof = proof(launch.id(), launch.externalSessionId());
            boolean done = prompts.settleForRun(launch.candidateRunId(), proof,
                    () -> settlement.finishSettled(intentId, proof));
            return done ? Result.ready(intents.require(intentId))
                    : Result.pending(intents.require(intentId), "CANDIDATE_PROMPT_IO_IN_FLIGHT");
        }
        return Result.ready(settlement.finishAfterCleanup(intentId));
    }

    private OpenCodeClient.SessionAttestation attestation(GenericCandidateInternalLaunchRow launch) {
        OpenCodeClient.SessionCreationPlan plan = launches.plan(launch);
        return new OpenCodeClient.SessionAttestation(
                launch.externalSessionId(), plan.canonicalDirectory(), plan.exactTitle(),
                plan.runtimeGenerationId(), plan.managed(), plan.internalMcpServer(),
                plan.endpointFingerprint(), plan.model(), plan.profile(), plan.permissionPolicy(),
                plan.permissionPolicyDigest(), plan.creationCredential(), plan.createRequestSha256(),
                OpenCodeClient.SessionAttestationKind.LOCAL_REQUEST_ATTESTED);
    }
    private String proof(String launchId, String remoteId) {
        return cleanup.list(launchId).stream().filter(row -> remoteId.equals(row.externalSessionId()))
                .map(GenericCandidateInternalLaunchCleanupRemoteRow::terminationProof)
                .filter(CandidateSessionTerminationProof::persisted).findFirst().orElseThrow();
    }

    enum Status { READY, PENDING, DISCONNECTED }
    record Result(Status status, GenericCandidateInternalTerminationIntentRow intent, String code) {
        static Result ready(GenericCandidateInternalTerminationIntentRow row) {
            return new Result(Status.READY, row, null);
        }
        static Result pending(GenericCandidateInternalTerminationIntentRow row, String code) {
            return new Result(Status.PENDING, row, code);
        }
        static Result disconnected(GenericCandidateInternalTerminationIntentRow row) {
            return new Result(Status.DISCONNECTED, row, row.lastErrorCode());
        }
    }
}
