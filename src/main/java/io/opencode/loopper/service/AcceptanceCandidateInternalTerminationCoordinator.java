package io.opencode.loopper.service;

import io.opencode.loopper.config.LoopperProperties;
import io.opencode.loopper.domain.AcceptanceCandidateInternalLaunchCleanupState;
import io.opencode.loopper.domain.AcceptanceCandidateInternalLaunchState;
import io.opencode.loopper.domain.AcceptanceCandidateInternalTerminationIntentState;
import io.opencode.loopper.persistence.AcceptanceCandidateInternalLaunchCleanupRemoteRow;
import io.opencode.loopper.persistence.AcceptanceCandidateInternalLaunchRow;
import io.opencode.loopper.persistence.AcceptanceCandidateInternalTerminationIntentRow;
import io.opencode.loopper.runtime.OpenCodeClient;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** Reentrant remote-I/O coordinator for one durable internal termination intent. */
@Component
final class AcceptanceCandidateInternalTerminationCoordinator {
    private final AcceptanceCandidateInternalTerminationIntentStore intents;
    private final AcceptanceCandidateInternalLaunchService launches;
    private final AcceptanceCandidateInternalLaunchCleanupLedger cleanup;
    private final AcceptanceCandidateInternalTerminationSettlementService settlement;
    private final CandidatePromptDispatchService prompts;
    private final Optional<CandidateRuntimeBindingService> bindings;
    private final OpenCodeClient openCode;
    private final Duration claimTtl;

    @Autowired
    AcceptanceCandidateInternalTerminationCoordinator(
            AcceptanceCandidateInternalTerminationIntentStore intents,
            AcceptanceCandidateInternalLaunchService launches,
            AcceptanceCandidateInternalLaunchCleanupLedger cleanup,
            AcceptanceCandidateInternalTerminationSettlementService settlement,
            CandidatePromptDispatchService prompts, Optional<CandidateRuntimeBindingService> bindings,
            OpenCodeClient openCode, LoopperProperties properties) {
        this(intents, launches, cleanup, settlement, prompts, bindings, openCode,
                AcceptanceCandidateLegacyHandoffCoordinator.claimTtl(properties));
    }

    AcceptanceCandidateInternalTerminationCoordinator(
            AcceptanceCandidateInternalTerminationIntentStore intents,
            AcceptanceCandidateInternalLaunchService launches,
            AcceptanceCandidateInternalLaunchCleanupLedger cleanup,
            AcceptanceCandidateInternalTerminationSettlementService settlement,
            CandidatePromptDispatchService prompts, CandidateRuntimeBindingService bindings,
            OpenCodeClient openCode, Duration claimTtl) {
        this(intents, launches, cleanup, settlement, prompts, Optional.of(bindings), openCode, claimTtl);
    }

    private AcceptanceCandidateInternalTerminationCoordinator(
            AcceptanceCandidateInternalTerminationIntentStore intents,
            AcceptanceCandidateInternalLaunchService launches,
            AcceptanceCandidateInternalLaunchCleanupLedger cleanup,
            AcceptanceCandidateInternalTerminationSettlementService settlement,
            CandidatePromptDispatchService prompts, Optional<CandidateRuntimeBindingService> bindings,
            OpenCodeClient openCode, Duration claimTtl) {
        this.intents = intents;
        this.launches = launches;
        this.cleanup = cleanup;
        this.settlement = settlement;
        this.prompts = prompts;
        this.bindings = bindings;
        this.openCode = openCode;
        this.claimTtl = claimTtl;
    }

    Result advance(String intentId) {
        AcceptanceCandidateInternalTerminationIntentRow intent = intents.requireIntent(intentId);
        AcceptanceCandidateInternalTerminationIntentState intentState = intentState(intent);
        if (intentState == AcceptanceCandidateInternalTerminationIntentState.COMPLETED
                || intentState == AcceptanceCandidateInternalTerminationIntentState.READY) {
            return ready(intentId, intent);
        }
        if (intentState == AcceptanceCandidateInternalTerminationIntentState.DISCONNECTED) {
            try { intent = intents.recover(intent); }
            catch (RuntimeException raced) { return Result.pending(intents.requireIntent(intentId), code(raced)); }
        }
        AcceptanceCandidateInternalLaunchRow launch = launches.require(intent.launchId());
        AcceptanceCandidateInternalLaunchState state = launchState(launch);
        if (state == AcceptanceCandidateInternalLaunchState.PREPARED) {
            try { return Result.ready(settlement.finishWithoutRemote(intentId)); }
            catch (RuntimeException failure) { return Result.pending(intents.requireIntent(intentId), code(failure)); }
        }
        if (state == AcceptanceCandidateInternalLaunchState.CANCELLED
                || state == AcceptanceCandidateInternalLaunchState.STALE
                || state == AcceptanceCandidateInternalLaunchState.FAILED_STOPPED) {
            try { return ready(intentId, intents.ready(intents.requireIntent(intentId))); }
            catch (RuntimeException failure) { return Result.pending(intents.requireIntent(intentId), code(failure)); }
        }
        List<AcceptanceCandidateInternalLaunchCleanupRemoteRow> remotes = cleanup.list(launch.id());
        if (state == AcceptanceCandidateInternalLaunchState.SETTLED) {
            if (!prompts.prepareRunTermination(launch.candidateRunId(), Instant.now())) {
                return Result.pending(intents.requireIntent(intentId), "CANDIDATE_PROMPT_IO_IN_FLIGHT");
            }
            if (remotes.isEmpty()) {
                try { remotes = settlement.registerCleanup(intentId, List.of(attestation(launch))); }
                catch (RuntimeException failure) { return disconnect(intentId, failure); }
            }
        } else if (remotes.isEmpty()) {
            if (state == AcceptanceCandidateInternalLaunchState.CREATED || launch.externalSessionId() != null) {
                try { remotes = settlement.registerCleanup(intentId, List.of(attestation(launch))); }
                catch (RuntimeException failure) { return disconnect(intentId, failure); }
            } else if (state == AcceptanceCandidateInternalLaunchState.CREATING
                    || state == AcceptanceCandidateInternalLaunchState.DISCONNECTED) {
                Result reconciled = reconcileUnknownCreate(intent, launch);
                if (reconciled != null) return reconciled;
                remotes = cleanup.list(launch.id());
            } else if (state != AcceptanceCandidateInternalLaunchState.STOPPING) {
                return Result.pending(intents.requireIntent(intentId), "ACCEPTANCE_INTERNAL_TERMINATION_STALE");
            }
        }
        return stopRegistered(intentId, launches.require(launch.id()), remotes);
    }

    private Result reconcileUnknownCreate(AcceptanceCandidateInternalTerminationIntentRow intent,
            AcceptanceCandidateInternalLaunchRow launch) {
        OpenCodeClient.SessionCreationPlan plan;
        OpenCodeClient.SessionLookup lookup;
        try {
            plan = launches.plan(launch);
            openCode.requireCandidateSessionReady(plan);
            lookup = openCode.findSessionsByExactTitle(plan);
        } catch (RuntimeException failure) {
            return disconnect(intent.id(), failure);
        }
        if (lookup == null || !lookup.supported()) {
            return disconnect(intent.id(), new ConflictException("OPENCODE_CREATE_RESULT_UNKNOWN",
                    "Exact-title lookup is unavailable during durable termination"));
        }
        List<OpenCodeClient.SessionAttestation> matches = lookup.matches();
        if (!strictMatches(plan, matches)) {
            return disconnect(intent.id(), new ConflictException("CANDIDATE_INTERNAL_ATTESTATION_MISMATCH",
                    "Termination lookup drifted from the frozen launch plan"));
        }
        if (matches.isEmpty()) {
            try {
                return Result.disconnected(settlement.disconnectCreateUnknown(intent.id(),
                        "OPENCODE_CREATE_RESULT_UNKNOWN", "Exact-title lookup found no remote"));
            } catch (RuntimeException failure) { return disconnect(intent.id(), failure); }
        }
        try {
            CandidateRuntimeBindingService binding = bindings.orElseThrow(() -> new ConflictException(
                    "CANDIDATE_RUNTIME_GUARD_DISABLED",
                    "Internal candidate termination attestation requires the runtime guard"));
            matches.forEach(match -> binding.bindInternalAttested(match, plan));
            settlement.registerCleanup(intent.id(), matches);
            return null;
        } catch (RuntimeException failure) {
            return disconnect(intent.id(), failure);
        }
    }

    private Result stopRegistered(String intentId, AcceptanceCandidateInternalLaunchRow launch,
            List<AcceptanceCandidateInternalLaunchCleanupRemoteRow> initial) {
        List<AcceptanceCandidateInternalLaunchCleanupRemoteRow> remotes = initial == null
                ? cleanup.list(launch.id()) : initial;
        if (remotes.isEmpty()) return Result.pending(intents.requireIntent(intentId), "CLEANUP_NOT_REGISTERED");
        for (AcceptanceCandidateInternalLaunchCleanupRemoteRow remote : remotes) {
            if (AcceptanceCandidateInternalLaunchCleanupState.STOPPED.name().equals(remote.state())) continue;
            AcceptanceCandidateInternalLaunchCleanupLedger.StopClaim claim = cleanup.claimStop(
                    launch.id(), remote.externalSessionId(), UUID.randomUUID().toString(),
                    Instant.now(), claimTtl);
            if (!claim.acquired()) return Result.pending(intents.requireIntent(intentId), claim.reason());
            remote = findRemote(launch.id(), remote.externalSessionId());
            if (remote.stopDispatchAttempted()) {
                try {
                    OpenCodeClient.SessionStatus status = openCode.sessionStatus(session(launch, remote));
                    if (status != null && status.completed()) {
                        cleanup.stopped(launch.id(), remote.externalSessionId(), claim,
                                CandidateSessionTerminationProof.REMOTE_COMPLETED.name());
                        continue;
                    }
                    cleanup.disconnected(launch.id(), remote.externalSessionId(), claim,
                            "OPENCODE_ACCEPTANCE_INTERNAL_STOP_RESULT_UNKNOWN",
                            status == null ? "No status returned" : status.state());
                    return disconnect(intentId, new ConflictException(
                            "OPENCODE_ACCEPTANCE_INTERNAL_STOP_RESULT_UNKNOWN", "Remote stop is unconfirmed"));
                } catch (RuntimeException failure) {
                    try { cleanup.disconnected(launch.id(), remote.externalSessionId(), claim,
                            "OPENCODE_ACCEPTANCE_INTERNAL_STOP_RESULT_UNKNOWN", failure.getMessage()); }
                    catch (RuntimeException raced) { failure.addSuppressed(raced); }
                    return disconnect(intentId, failure);
                }
            }
            AcceptanceCandidateInternalLaunchCleanupLedger.StopCheckpoint checkpoint =
                    cleanup.markStopDispatchStarted(launch.id(), remote.externalSessionId(), claim);
            if (!checkpoint.newlyStarted()) {
                return Result.pending(intents.requireIntent(intentId), "STOP_DISPATCHED");
            }
            try {
                String proof = CandidateSessionTerminationProof.from(
                        openCode.abortWithConfirmation(session(launch, remote))).name();
                cleanup.stopped(launch.id(), remote.externalSessionId(), claim, proof);
            } catch (RuntimeException failure) {
                try { cleanup.disconnected(launch.id(), remote.externalSessionId(), claim,
                        "OPENCODE_ACCEPTANCE_INTERNAL_STOP_RESULT_UNKNOWN", failure.getMessage()); }
                catch (RuntimeException raced) { failure.addSuppressed(raced); }
                return disconnect(intentId, failure);
            }
        }
        if (launchState(launches.require(launch.id())) == AcceptanceCandidateInternalLaunchState.SETTLED) {
            String proof = proofFor(launch.id(), launch.externalSessionId());
            AtomicReference<AcceptanceCandidateInternalTerminationSettlementService.SettledRun> settled =
                    new AtomicReference<>();
            try {
                boolean done = prompts.settleForRun(launch.candidateRunId(), proof,
                        () -> settled.set(settlement.finishSettled(intentId, proof)));
                return done ? Result.ready(intents.requireIntent(intentId), Optional.ofNullable(settled.get())
                        .orElseGet(() -> settlement.requireSettledRun(intentId)))
                        : Result.pending(intents.requireIntent(intentId), "CANDIDATE_PROMPT_IO_IN_FLIGHT");
            } catch (RuntimeException failure) { return Result.pending(intents.requireIntent(intentId), code(failure)); }
        }
        try { return Result.ready(settlement.finishAfterCleanup(intentId)); }
        catch (RuntimeException failure) { return Result.pending(intents.requireIntent(intentId), code(failure)); }
    }

    private Result disconnect(String intentId, RuntimeException failure) {
        try {
            return Result.disconnected(intents.disconnected(
                    intents.requireIntent(intentId), code(failure), failure.getMessage()));
        } catch (RuntimeException raced) {
            return Result.pending(intents.requireIntent(intentId), code(raced));
        }
    }

    private Result ready(String intentId, AcceptanceCandidateInternalTerminationIntentRow intent) {
        return Result.ready(intent, settlement.findSettledRun(intentId).orElse(null));
    }

    private AcceptanceCandidateInternalLaunchCleanupRemoteRow findRemote(String launchId, String remoteId) {
        return cleanup.list(launchId).stream().filter(row -> remoteId.equals(row.externalSessionId()))
                .findFirst().orElseThrow();
    }

    private String proofFor(String launchId, String remoteId) {
        return cleanup.list(launchId).stream().filter(row -> remoteId.equals(row.externalSessionId()))
                .map(AcceptanceCandidateInternalLaunchCleanupRemoteRow::terminationProof)
                .filter(CandidateSessionTerminationProof::persisted).findFirst().orElseThrow();
    }

    private OpenCodeClient.SessionAttestation attestation(AcceptanceCandidateInternalLaunchRow launch) {
        OpenCodeClient.SessionCreationPlan plan = launches.plan(launch);
        return new OpenCodeClient.SessionAttestation(
                launch.externalSessionId(), plan.canonicalDirectory(), plan.exactTitle(),
                plan.runtimeGenerationId(), plan.managed(), plan.internalMcpServer(), plan.endpointFingerprint(),
                plan.model(), plan.profile(), plan.permissionPolicy(), plan.permissionPolicyDigest(),
                plan.creationCredential(), plan.createRequestSha256(),
                OpenCodeClient.SessionAttestationKind.LOCAL_REQUEST_ATTESTED);
    }

    private static OpenCodeClient.OpenCodeSession session(AcceptanceCandidateInternalLaunchRow launch,
            AcceptanceCandidateInternalLaunchCleanupRemoteRow remote) {
        return new OpenCodeClient.OpenCodeSession(remote.externalSessionId(), Path.of(launch.canonicalDirectory()),
                remote.runtimeGenerationId(), launch.internalMcpServer());
    }

    private static boolean strictMatches(OpenCodeClient.SessionCreationPlan plan,
            List<OpenCodeClient.SessionAttestation> matches) {
        if (matches == null || matches.stream().anyMatch(match -> match == null
                || match.attestationKind() != OpenCodeClient.SessionAttestationKind.LOCAL_REQUEST_ATTESTED
                || !plan.equals(match.plan()))) return false;
        HashSet<String> ids = new HashSet<>();
        return matches.stream().allMatch(match -> ids.add(match.remoteId()));
    }

    private static AcceptanceCandidateInternalLaunchState launchState(AcceptanceCandidateInternalLaunchRow row) {
        return AcceptanceCandidateInternalLaunchState.valueOf(row.state());
    }

    private static AcceptanceCandidateInternalTerminationIntentState intentState(
            AcceptanceCandidateInternalTerminationIntentRow row) {
        return AcceptanceCandidateInternalTerminationIntentState.valueOf(row.state());
    }

    private static String code(RuntimeException failure) {
        return failure instanceof ConflictException conflict
                ? conflict.code() : "OPENCODE_ACCEPTANCE_INTERNAL_TERMINATION_FAILED";
    }

    enum Status { READY, PENDING, DISCONNECTED }
    record Result(Status status, AcceptanceCandidateInternalTerminationIntentRow intent, String code,
                  Optional<AcceptanceCandidateInternalTerminationSettlementService.SettledRun> settledRun) {
        Result {
            if (status == null || intent == null || settledRun == null) throw new IllegalArgumentException();
        }
        static Result ready(AcceptanceCandidateInternalTerminationIntentRow row) {
            return new Result(Status.READY, row, null, Optional.empty());
        }
        static Result ready(AcceptanceCandidateInternalTerminationIntentRow row,
                AcceptanceCandidateInternalTerminationSettlementService.SettledRun settledRun) {
            return new Result(Status.READY, row, null, Optional.ofNullable(settledRun));
        }
        static Result pending(AcceptanceCandidateInternalTerminationIntentRow row, String code) {
            return new Result(Status.PENDING, row, code, Optional.empty());
        }
        static Result disconnected(AcceptanceCandidateInternalTerminationIntentRow row) {
            return new Result(Status.DISCONNECTED, row, row.lastErrorCode(), Optional.empty());
        }
    }
}
