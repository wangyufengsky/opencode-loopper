package io.opencode.loopper.service;

import io.opencode.loopper.config.LoopperProperties;
import io.opencode.loopper.domain.GenericCandidateInternalLaunchCleanupState;
import io.opencode.loopper.domain.GenericCandidateInternalLaunchState;
import io.opencode.loopper.persistence.GenericCandidateInternalLaunchCleanupRemoteRow;
import io.opencode.loopper.persistence.GenericCandidateInternalLaunchRow;
import io.opencode.loopper.runtime.OpenCodeClient;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** Remote-I/O coordinator for one already PREPARED V57 launch; it stops before INITIAL prompt dispatch. */
@Component
final class GenericCandidateInternalLaunchCoordinator {
    private final GenericCandidateInternalLaunchService launches;
    private final GenericCandidateInternalLaunchCleanupLedger cleanup;
    private final GenericCandidateInternalLaunchSettlementService settlements;
    private final Optional<CandidateRuntimeBindingService> bindings;
    private final Optional<GenericCandidateInternalLaunchPreIoGuard> preIoGuard;
    private final OpenCodeClient openCode;
    private final Duration claimTtl;

    String actualToolName(GenericCandidateInternalLaunchRow launch) {
        return launches.actualToolName(launch);
    }

    @Autowired
    GenericCandidateInternalLaunchCoordinator(
            GenericCandidateInternalLaunchService launches,
            GenericCandidateInternalLaunchCleanupLedger cleanup,
            GenericCandidateInternalLaunchSettlementService settlements,
            Optional<CandidateRuntimeBindingService> bindings,
            GenericCandidateInternalLaunchPreIoGuard preIoGuard,
            OpenCodeClient openCode, LoopperProperties properties) {
        this(launches, cleanup, settlements, bindings, Optional.of(preIoGuard), openCode,
                AcceptanceCandidateLegacyHandoffCoordinator.claimTtl(properties));
    }

    GenericCandidateInternalLaunchCoordinator(
            GenericCandidateInternalLaunchService launches,
            GenericCandidateInternalLaunchCleanupLedger cleanup,
            GenericCandidateInternalLaunchSettlementService settlements,
            CandidateRuntimeBindingService bindings,
            OpenCodeClient openCode, Duration claimTtl) {
        this(launches, cleanup, settlements, Optional.of(bindings), Optional.empty(), openCode, claimTtl);
    }

    GenericCandidateInternalLaunchCoordinator(
            GenericCandidateInternalLaunchService launches,
            GenericCandidateInternalLaunchCleanupLedger cleanup,
            GenericCandidateInternalLaunchSettlementService settlements,
            CandidateRuntimeBindingService bindings,
            GenericCandidateInternalLaunchPreIoGuard preIoGuard,
            OpenCodeClient openCode, Duration claimTtl) {
        this(launches, cleanup, settlements, Optional.of(bindings), Optional.of(preIoGuard), openCode, claimTtl);
    }

    private GenericCandidateInternalLaunchCoordinator(
            GenericCandidateInternalLaunchService launches,
            GenericCandidateInternalLaunchCleanupLedger cleanup,
            GenericCandidateInternalLaunchSettlementService settlements,
            Optional<CandidateRuntimeBindingService> bindings,
            Optional<GenericCandidateInternalLaunchPreIoGuard> preIoGuard,
            OpenCodeClient openCode, Duration claimTtl) {
        this.launches = launches;
        this.cleanup = cleanup;
        this.settlements = settlements;
        this.bindings = bindings;
        this.preIoGuard = preIoGuard;
        this.openCode = openCode;
        this.claimTtl = claimTtl;
    }

    Result advance(String launchId) {
        GenericCandidateInternalLaunchRow launch = launches.require(launchId);
        GenericCandidateInternalLaunchState state = state(launch);
        if (state == GenericCandidateInternalLaunchState.SETTLED
                || state == GenericCandidateInternalLaunchState.CREATED) return settle(launch);
        if (state == GenericCandidateInternalLaunchState.STOPPING
                || !launches.cleanup(launch.id()).isEmpty()) return cleanup(launch);
        if (state == GenericCandidateInternalLaunchState.FAILED_STOPPED) {
            return Result.failed(launch, launch.lastErrorCode(), launch.lastErrorDetail());
        }
        if (state == GenericCandidateInternalLaunchState.COMPLETED
                || state == GenericCandidateInternalLaunchState.CANCELLED
                || state == GenericCandidateInternalLaunchState.STALE) {
            return Result.blocked(launch, "GENERIC_CANDIDATE_INTERNAL_LAUNCH_TERMINAL", launch.state());
        }

        GenericCandidateInternalLaunchService.CreateClaim claim = launches.claimCreate(
                launch.id(), UUID.randomUUID().toString(), Instant.now(), claimTtl);
        if (!claim.acquired()) {
            return Result.pending(launch, "GENERIC_CANDIDATE_INTERNAL_CREATE_CLAIMED", claim.reason());
        }
        launch = launches.require(launch.id());
        OpenCodeClient.SessionCreationPlan plan;
        try {
            plan = launches.plan(launch);
            if (preIoGuard.isPresent()) preIoGuard.orElseThrow().require(launch);
            openCode.requireCandidateSessionReady(plan);
        } catch (RuntimeException failure) {
            return preOrPostCheckpointFailure(launch, claim, "OWNER_REVALIDATION", failure);
        }

        OpenCodeClient.SessionLookup lookup;
        try { lookup = openCode.findSessionsByExactTitle(plan); }
        catch (RuntimeException failure) {
            return preOrPostCheckpointFailure(launch, claim, "CREATE_LOOKUP", failure);
        }
        if (lookup == null || !lookup.supported()) {
            return preOrPostCheckpointFailure(launch, claim, "CREATE_LOOKUP",
                    new ConflictException("OPENCODE_EXACT_LOOKUP_UNSUPPORTED",
                            "V57 generic candidate requires exact-title recovery support"));
        }
        List<OpenCodeClient.SessionAttestation> matches = lookup.matches();
        if (!strictMatches(plan, matches)) {
            return preOrPostCheckpointFailure(launch, claim, "REMOTE_ATTESTATION",
                    new ConflictException("GENERIC_CANDIDATE_INTERNAL_ATTESTATION_MISMATCH",
                            "Exact-title lookup drifted from the frozen V57 plan"));
        }
        if (matches.size() > 1) {
            try {
                matches.forEach(match -> bindings().bindInternalAttested(match, plan));
                launches.registerAmbiguity(launch.id(), claim, matches);
                return cleanup(launches.require(launch.id()));
            } catch (RuntimeException failure) {
                return Result.blocked(launches.require(launch.id()), code(failure), failure.getMessage());
            }
        }
        if (matches.size() == 1) return adopt(launch, claim, plan, matches.getFirst());
        if (launch.createDispatchAttempted()) {
            return Result.pending(launches.disconnected(launch.id(), claim, "CREATE_LOOKUP",
                    "OPENCODE_CREATE_RESULT_UNKNOWN",
                    "Create was dispatched but exact-title lookup found no remote"),
                    "OPENCODE_CREATE_RESULT_UNKNOWN", null);
        }

        GenericCandidateInternalLaunchService.DispatchCheckpoint checkpoint;
        try { checkpoint = launches.markCreateDispatchStarted(launch.id(), claim); }
        catch (RuntimeException failure) {
            return Result.blocked(launches.require(launch.id()), code(failure), failure.getMessage());
        }
        if (!checkpoint.newlyStarted()) {
            return Result.pending(checkpoint.row(), "OPENCODE_CREATE_RESULT_UNKNOWN", null);
        }
        OpenCodeClient.SessionAttestation created;
        try { created = openCode.createSession(plan); }
        catch (RuntimeException uncertain) {
            return Result.pending(launches.disconnected(launch.id(), claim, "CREATE_POST",
                    "OPENCODE_CREATE_RESULT_UNKNOWN", uncertain.getMessage()),
                    "OPENCODE_CREATE_RESULT_UNKNOWN", uncertain.getMessage());
        }
        if (!strictMatches(plan, List.of(created))) {
            return Result.blocked(launches.disconnected(launch.id(), claim, "REMOTE_ATTESTATION",
                    "GENERIC_CANDIDATE_INTERNAL_ATTESTATION_MISMATCH",
                    "Create response drifted from the frozen V57 plan"),
                    "GENERIC_CANDIDATE_INTERNAL_ATTESTATION_MISMATCH", null);
        }
        return adopt(launches.require(launch.id()), claim, plan, created);
    }

    private Result adopt(GenericCandidateInternalLaunchRow launch,
            GenericCandidateInternalLaunchService.CreateClaim claim,
            OpenCodeClient.SessionCreationPlan plan, OpenCodeClient.SessionAttestation remote) {
        try {
            if (!launch.createDispatchAttempted()) {
                launch = launches.markCreateDispatchStarted(launch.id(), claim).row();
            }
            bindings().bindInternalAttested(remote, plan);
            return settle(launches.created(launch.id(), claim, remote));
        } catch (RuntimeException failure) {
            try {
                launches.registerCleanup(launch.id(), claim, List.of(remote), "LAUNCH_AMBIGUITY",
                        "OWNER_REVALIDATION", code(failure), failure.getMessage());
                return cleanup(launches.require(launch.id()));
            } catch (RuntimeException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            return Result.blocked(launches.require(launch.id()), code(failure), failure.getMessage());
        }
    }

    private Result settle(GenericCandidateInternalLaunchRow launch) {
        try {
            GenericCandidateInternalLaunchSettlementService.Settlement settlement =
                    settlements.settle(launch.id());
            return Result.settled(settlement.launch(), remote(settlement.launch()), settlement.run());
        } catch (RuntimeException failure) {
            GenericCandidateInternalLaunchRow current = launches.require(launch.id());
            return state(current) == GenericCandidateInternalLaunchState.CREATED
                    ? Result.created(current, remote(current), code(failure), failure.getMessage())
                    : Result.blocked(current, code(failure), failure.getMessage());
        }
    }

    private Result cleanup(GenericCandidateInternalLaunchRow launch) {
        for (GenericCandidateInternalLaunchCleanupRemoteRow item : cleanup.list(launch.id())) {
            if (GenericCandidateInternalLaunchCleanupState.STOPPED.name().equals(item.state())) continue;
            GenericCandidateInternalLaunchCleanupLedger.StopClaim claim = cleanup.claimStop(
                    launch.id(), item.externalSessionId(), UUID.randomUUID().toString(),
                    Instant.now(), claimTtl);
            if (!claim.acquired()) return Result.cleanup(launches.require(launch.id()), claim.reason());
            String remoteId = item.externalSessionId();
            item = cleanup.list(launch.id()).stream()
                    .filter(row -> remoteId.equals(row.externalSessionId())).findFirst().orElseThrow();
            OpenCodeClient.OpenCodeSession session = new OpenCodeClient.OpenCodeSession(
                    item.externalSessionId(), Path.of(launch.canonicalDirectory()),
                    item.runtimeGenerationId(), launch.internalMcpServer());
            if (item.stopDispatchAttempted()) {
                try {
                    OpenCodeClient.SessionStatus status = openCode.sessionStatus(session);
                    if (status != null && status.completed()) {
                        cleanup.stopped(launch.id(), item.externalSessionId(), claim,
                                CandidateSessionTerminationProof.REMOTE_COMPLETED.name());
                        continue;
                    }
                    cleanup.disconnected(launch.id(), item.externalSessionId(), claim,
                            "OPENCODE_GENERIC_CANDIDATE_STOP_RESULT_UNKNOWN",
                            status == null ? "No status returned" : status.state());
                } catch (RuntimeException uncertain) {
                    cleanup.disconnected(launch.id(), item.externalSessionId(), claim,
                            "OPENCODE_GENERIC_CANDIDATE_STOP_RESULT_UNKNOWN", uncertain.getMessage());
                }
                return Result.cleanup(launches.require(launch.id()), "STOP_RESULT_UNKNOWN");
            }
            GenericCandidateInternalLaunchCleanupLedger.StopCheckpoint checkpoint =
                    cleanup.markStopDispatchStarted(launch.id(), item.externalSessionId(), claim);
            if (!checkpoint.newlyStarted()) {
                return Result.cleanup(launches.require(launch.id()), "STOP_DISPATCHED");
            }
            try {
                String proof = CandidateSessionTerminationProof.from(
                        openCode.abortWithConfirmation(session)).name();
                cleanup.stopped(launch.id(), item.externalSessionId(), claim, proof);
            } catch (RuntimeException uncertain) {
                cleanup.disconnected(launch.id(), item.externalSessionId(), claim,
                        "OPENCODE_GENERIC_CANDIDATE_STOP_RESULT_UNKNOWN", uncertain.getMessage());
                return Result.cleanup(launches.require(launch.id()), "STOP_RESULT_UNKNOWN");
            }
        }
        GenericCandidateInternalLaunchRow finished = launches.finishAfterCleanup(launch.id());
        return state(finished) == GenericCandidateInternalLaunchState.FAILED_STOPPED
                ? Result.failed(finished, finished.lastErrorCode(), finished.lastErrorDetail())
                : Result.cleanup(finished, "CLEANUP_PENDING");
    }

    private Result preOrPostCheckpointFailure(GenericCandidateInternalLaunchRow launch,
            GenericCandidateInternalLaunchService.CreateClaim claim,
            String phase, RuntimeException failure) {
        String code = code(failure);
        GenericCandidateInternalLaunchRow recorded = launch.createDispatchAttempted()
                ? launches.disconnected(launch.id(), claim, phase, code, failure.getMessage())
                : launches.releaseCreateClaim(launch.id(), claim, phase, code, failure.getMessage());
        return Result.blocked(recorded, code, failure.getMessage());
    }

    private CandidateRuntimeBindingService bindings() {
        return bindings.orElseThrow(() -> new ConflictException(
                "CANDIDATE_RUNTIME_BINDING_UNAVAILABLE", "V57 generic candidate runtime binding is unavailable"));
    }

    private static boolean strictMatches(OpenCodeClient.SessionCreationPlan plan,
            List<OpenCodeClient.SessionAttestation> matches) {
        if (matches == null || matches.stream().anyMatch(match -> match == null
                || match.attestationKind() != OpenCodeClient.SessionAttestationKind.LOCAL_REQUEST_ATTESTED
                || !plan.equals(match.plan()))) return false;
        HashSet<String> ids = new HashSet<>();
        return matches.stream().allMatch(match -> ids.add(match.remoteId()));
    }

    private static OpenCodeClient.OpenCodeSession remote(GenericCandidateInternalLaunchRow launch) {
        return blank(launch.externalSessionId()) ? null : new OpenCodeClient.OpenCodeSession(
                launch.externalSessionId(), Path.of(launch.canonicalDirectory()),
                launch.runtimeGenerationId(), launch.internalMcpServer());
    }
    private static GenericCandidateInternalLaunchState state(GenericCandidateInternalLaunchRow launch) {
        return GenericCandidateInternalLaunchState.valueOf(launch.state());
    }
    private static String code(RuntimeException failure) {
        if (failure instanceof ConflictException conflict) return conflict.code();
        if (failure instanceof io.opencode.loopper.domain.SessionFailure session) return session.code();
        return "OPENCODE_GENERIC_CANDIDATE_INTERNAL_LAUNCH_FAILED";
    }
    private static boolean blank(String value) { return value == null || value.isBlank(); }

    enum Status { PENDING, BLOCKED, CREATED, SETTLED, CLEANUP_PENDING, FAILED_STOPPED }
    record Result(Status status, GenericCandidateInternalLaunchRow launch,
                  OpenCodeClient.OpenCodeSession remote,
                  MachineCandidateSubmission.RunSnapshot run,
                  String code, String detail) {
        static Result pending(GenericCandidateInternalLaunchRow row, String code, String detail) {
            return new Result(Status.PENDING, row, null, null, code, detail);
        }
        static Result blocked(GenericCandidateInternalLaunchRow row, String code, String detail) {
            return new Result(Status.BLOCKED, row, null, null, code, detail);
        }
        static Result created(GenericCandidateInternalLaunchRow row,
                OpenCodeClient.OpenCodeSession remote, String code, String detail) {
            return new Result(Status.CREATED, row, remote, null, code, detail);
        }
        static Result settled(GenericCandidateInternalLaunchRow row,
                OpenCodeClient.OpenCodeSession remote, MachineCandidateSubmission.RunSnapshot run) {
            return new Result(Status.SETTLED, row, remote, run, null, null);
        }
        static Result cleanup(GenericCandidateInternalLaunchRow row, String detail) {
            return new Result(Status.CLEANUP_PENDING, row, null, null,
                    "OPENCODE_SESSION_CREATION_AMBIGUOUS", detail);
        }
        static Result failed(GenericCandidateInternalLaunchRow row, String code, String detail) {
            return new Result(Status.FAILED_STOPPED, row, null, null, code, detail);
        }
    }
}
