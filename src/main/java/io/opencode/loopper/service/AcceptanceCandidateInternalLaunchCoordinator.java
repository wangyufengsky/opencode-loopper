package io.opencode.loopper.service;

import io.opencode.loopper.config.LoopperProperties;
import io.opencode.loopper.domain.AcceptanceCandidateInternalLaunchCleanupState;
import io.opencode.loopper.domain.AcceptanceCandidateInternalLaunchState;
import io.opencode.loopper.persistence.AcceptanceCandidateInternalLaunchCleanupRemoteRow;
import io.opencode.loopper.persistence.AcceptanceCandidateInternalLaunchRow;
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

/**
 * Remote-I/O coordinator for the recoverable Acceptance internal-MCP launch.
 * It deliberately stops at a settled OPEN run and never sends the initial prompt.
 */
@Component
final class AcceptanceCandidateInternalLaunchCoordinator {
    private final AcceptanceCandidateInternalLaunchService launches;
    private final AcceptanceCandidateInternalLaunchCleanupLedger cleanup;
    private final AcceptanceCandidateInternalLaunchSettlementService settlements;
    private final Optional<CandidateRuntimeBindingService> bindings;
    private final OpenCodeClient openCode;
    private final Duration claimTtl;

    @Autowired
    AcceptanceCandidateInternalLaunchCoordinator(
            AcceptanceCandidateInternalLaunchService launches,
            AcceptanceCandidateInternalLaunchCleanupLedger cleanup,
            AcceptanceCandidateInternalLaunchSettlementService settlements,
            Optional<CandidateRuntimeBindingService> bindings,
            OpenCodeClient openCode, LoopperProperties properties) {
        this(launches, cleanup, settlements, bindings, openCode,
                AcceptanceCandidateLegacyHandoffCoordinator.claimTtl(properties));
    }

    AcceptanceCandidateInternalLaunchCoordinator(
            AcceptanceCandidateInternalLaunchService launches,
            AcceptanceCandidateInternalLaunchCleanupLedger cleanup,
            AcceptanceCandidateInternalLaunchSettlementService settlements,
            CandidateRuntimeBindingService bindings,
            OpenCodeClient openCode, Duration claimTtl) {
        this(launches, cleanup, settlements, Optional.of(bindings), openCode, claimTtl);
    }

    private AcceptanceCandidateInternalLaunchCoordinator(
            AcceptanceCandidateInternalLaunchService launches,
            AcceptanceCandidateInternalLaunchCleanupLedger cleanup,
            AcceptanceCandidateInternalLaunchSettlementService settlements,
            Optional<CandidateRuntimeBindingService> bindings,
            OpenCodeClient openCode, Duration claimTtl) {
        this.launches = launches;
        this.cleanup = cleanup;
        this.settlements = settlements;
        this.bindings = bindings;
        this.openCode = openCode;
        this.claimTtl = claimTtl;
    }

    Result advance(String compilationId) {
        AcceptanceCandidateInternalLaunchRow launch = launches.requireForCompilation(compilationId);
        AcceptanceCandidateInternalLaunchState state = state(launch);
        if (state == AcceptanceCandidateInternalLaunchState.SETTLED) return settle(launch);
        if (state == AcceptanceCandidateInternalLaunchState.CREATED) return settle(launch);
        if (state == AcceptanceCandidateInternalLaunchState.STOPPING
                || !launches.cleanup(launch.id()).isEmpty()) return cleanup(launch);
        if (state == AcceptanceCandidateInternalLaunchState.FAILED_STOPPED) {
            return Result.failed(launch, launch.lastErrorCode(), launch.lastErrorDetail());
        }
        if (state == AcceptanceCandidateInternalLaunchState.CANCELLED
                || state == AcceptanceCandidateInternalLaunchState.STALE) {
            return Result.blocked(launch, "ACCEPTANCE_INTERNAL_LAUNCH_TERMINAL", launch.state());
        }

        AcceptanceCandidateInternalLaunchService.CreateClaim claim = launches.claimCreate(
                launch.id(), UUID.randomUUID().toString(), Instant.now(), claimTtl);
        if (!claim.acquired()) return Result.pending(launch, "ACCEPTANCE_INTERNAL_CREATE_CLAIMED", claim.reason());
        launch = launches.require(launch.id());
        OpenCodeClient.SessionCreationPlan plan;
        try {
            plan = launches.plan(launch);
            // This is intentionally the first remote boundary.
            openCode.requireCandidateSessionReady(plan);
        } catch (RuntimeException failure) {
            return preOrPostCheckpointFailure(launch, claim, "OWNER_REVALIDATION", failure);
        }

        OpenCodeClient.SessionLookup lookup;
        try {
            lookup = openCode.findSessionsByExactTitle(plan);
        } catch (RuntimeException failure) {
            return preOrPostCheckpointFailure(launch, claim, "CREATE_LOOKUP", failure);
        }
        if (lookup == null) {
            return preOrPostCheckpointFailure(launch, claim, "CREATE_LOOKUP",
                    new ConflictException("OPENCODE_SESSION_LOOKUP_INVALID", "Exact-title lookup returned no result"));
        }
        if (!lookup.supported()) {
            if (!launch.createDispatchAttempted()) {
                return Result.legacy(launches.mechanicalLegacyFallback(launch.id(), claim));
            }
            return Result.pending(launches.disconnected(launch.id(), claim, "CREATE_LOOKUP",
                    "OPENCODE_CREATE_RESULT_UNKNOWN",
                    "Exact-title lookup is unsupported after the create checkpoint"),
                    "OPENCODE_CREATE_RESULT_UNKNOWN", null);
        }
        List<OpenCodeClient.SessionAttestation> matches = lookup.matches();
        if (!strictMatches(plan, matches)) {
            return preOrPostCheckpointFailure(launch, claim, "REMOTE_ATTESTATION",
                    new ConflictException("CANDIDATE_INTERNAL_ATTESTATION_MISMATCH",
                            "Exact-title lookup attestation drifted from the frozen plan"));
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
        if (matches.size() == 1) {
            return adopt(launch, claim, plan, matches.get(0));
        }
        if (launch.createDispatchAttempted()) {
            return Result.pending(launches.disconnected(launch.id(), claim, "CREATE_LOOKUP",
                    "OPENCODE_CREATE_RESULT_UNKNOWN",
                    "Create was dispatched but exact-title lookup found no remote"),
                    "OPENCODE_CREATE_RESULT_UNKNOWN", null);
        }

        AcceptanceCandidateInternalLaunchService.DispatchCheckpoint checkpoint;
        try {
            checkpoint = launches.markCreateDispatchStarted(launch.id(), claim);
        } catch (RuntimeException failure) {
            return Result.blocked(launches.require(launch.id()), code(failure), failure.getMessage());
        }
        if (!checkpoint.newlyStarted()) {
            return Result.pending(checkpoint.row(), "OPENCODE_CREATE_RESULT_UNKNOWN", null);
        }
        OpenCodeClient.SessionAttestation created;
        try {
            created = openCode.createSession(plan);
        } catch (RuntimeException uncertain) {
            AcceptanceCandidateInternalLaunchRow disconnected = launches.disconnected(
                    launch.id(), claim, "CREATE_POST", "OPENCODE_CREATE_RESULT_UNKNOWN", uncertain.getMessage());
            return Result.pending(disconnected, "OPENCODE_CREATE_RESULT_UNKNOWN", uncertain.getMessage());
        }
        if (!strictMatches(plan, List.of(created))) {
            return Result.blocked(launches.disconnected(launch.id(), claim, "REMOTE_ATTESTATION",
                    "CANDIDATE_INTERNAL_ATTESTATION_MISMATCH",
                    "Create response drifted from the frozen plan"),
                    "CANDIDATE_INTERNAL_ATTESTATION_MISMATCH", null);
        }
        return adopt(launches.require(launch.id()), claim, plan, created);
    }

    private Result adopt(AcceptanceCandidateInternalLaunchRow launch,
            AcceptanceCandidateInternalLaunchService.CreateClaim claim,
            OpenCodeClient.SessionCreationPlan plan,
            OpenCodeClient.SessionAttestation attestation) {
        try {
            if (!launch.createDispatchAttempted()) {
                launch = launches.markCreateDispatchStarted(launch.id(), claim).row();
            }
            bindings().bindInternalAttested(attestation, plan);
            AcceptanceCandidateInternalLaunchRow created = launches.created(launch.id(), claim, attestation);
            return settle(created);
        } catch (RuntimeException failure) {
            // The attested remote exists even when binding or owner revalidation fails.
            // Register it unconditionally so a post-create failure cannot orphan a writer.
            try {
                launches.registerCleanup(launch.id(), claim, List.of(attestation),
                        "OWNER_REVALIDATION", code(failure), failure.getMessage());
                return cleanup(launches.require(launch.id()));
            } catch (RuntimeException cleanupRegistrationFailure) {
                failure.addSuppressed(cleanupRegistrationFailure);
            }
            return Result.blocked(launches.require(launch.id()), code(failure), failure.getMessage());
        }
    }

    private Result settle(AcceptanceCandidateInternalLaunchRow launch) {
        try {
            AcceptanceCandidateInternalLaunchSettlementService.Settlement settlement = settlements.settle(launch.id());
            return Result.settled(settlement.launch(), remote(settlement.launch()), settlement.run(),
                    launches.actualToolName(settlement.launch()));
        } catch (RuntimeException failure) {
            AcceptanceCandidateInternalLaunchRow current = launches.require(launch.id());
            return state(current) == AcceptanceCandidateInternalLaunchState.CREATED
                    ? Result.created(current, remote(current), code(failure), failure.getMessage())
                    : Result.blocked(current, code(failure), failure.getMessage());
        }
    }

    private Result cleanup(AcceptanceCandidateInternalLaunchRow launch) {
        for (AcceptanceCandidateInternalLaunchCleanupRemoteRow remote : cleanup.list(launch.id())) {
            if (AcceptanceCandidateInternalLaunchCleanupState.STOPPED.name().equals(remote.state())) continue;
            AcceptanceCandidateInternalLaunchCleanupLedger.StopClaim claim = cleanup.claimStop(
                    launch.id(), remote.externalSessionId(), UUID.randomUUID().toString(),
                    Instant.now(), claimTtl);
            if (!claim.acquired()) return Result.cleanup(launches.require(launch.id()), claim.reason());
            String remoteId = remote.externalSessionId();
            remote = cleanup.list(launch.id()).stream()
                    .filter(item -> item.externalSessionId().equals(remoteId))
                    .findFirst().orElseThrow();
            OpenCodeClient.OpenCodeSession session = new OpenCodeClient.OpenCodeSession(
                    remote.externalSessionId(), Path.of(launch.canonicalDirectory()),
                    remote.runtimeGenerationId(), launch.internalMcpServer());
            if (remote.stopDispatchAttempted()) {
                try {
                    OpenCodeClient.SessionStatus status = openCode.sessionStatus(session);
                    if (status != null && status.completed()) {
                        cleanup.stopped(launch.id(), remote.externalSessionId(), claim,
                                CandidateSessionTerminationProof.REMOTE_COMPLETED.name());
                        continue;
                    }
                    cleanup.disconnected(launch.id(), remote.externalSessionId(), claim,
                            "OPENCODE_ACCEPTANCE_INTERNAL_STOP_RESULT_UNKNOWN",
                            status == null ? "No status returned" : status.state());
                } catch (RuntimeException uncertain) {
                    cleanup.disconnected(launch.id(), remote.externalSessionId(), claim,
                            "OPENCODE_ACCEPTANCE_INTERNAL_STOP_RESULT_UNKNOWN", uncertain.getMessage());
                }
                return Result.cleanup(launches.require(launch.id()), "STOP_RESULT_UNKNOWN");
            }
            AcceptanceCandidateInternalLaunchCleanupLedger.StopCheckpoint checkpoint =
                    cleanup.markStopDispatchStarted(launch.id(), remote.externalSessionId(), claim);
            if (!checkpoint.newlyStarted()) return Result.cleanup(launches.require(launch.id()), "STOP_DISPATCHED");
            try {
                String proof = CandidateSessionTerminationProof.from(
                        openCode.abortWithConfirmation(session)).name();
                cleanup.stopped(launch.id(), remote.externalSessionId(), claim, proof);
            } catch (RuntimeException uncertain) {
                cleanup.disconnected(launch.id(), remote.externalSessionId(), claim,
                        "OPENCODE_ACCEPTANCE_INTERNAL_STOP_RESULT_UNKNOWN", uncertain.getMessage());
                return Result.cleanup(launches.require(launch.id()), "STOP_RESULT_UNKNOWN");
            }
        }
        AcceptanceCandidateInternalLaunchRow finished = launches.finishAfterCleanup(launch.id());
        return state(finished) == AcceptanceCandidateInternalLaunchState.FAILED_STOPPED
                ? Result.failed(finished, finished.lastErrorCode(), finished.lastErrorDetail())
                : Result.cleanup(finished, "CLEANUP_PENDING");
    }

    private Result preOrPostCheckpointFailure(AcceptanceCandidateInternalLaunchRow launch,
            AcceptanceCandidateInternalLaunchService.CreateClaim claim,
            String phase, RuntimeException failure) {
        String code = code(failure);
        AcceptanceCandidateInternalLaunchRow recorded = launch.createDispatchAttempted()
                ? launches.disconnected(launch.id(), claim, phase, code, failure.getMessage())
                : launches.releaseCreateClaim(launch.id(), claim, phase, code, failure.getMessage());
        return Result.blocked(recorded, code, failure.getMessage());
    }

    private static boolean strictMatches(OpenCodeClient.SessionCreationPlan plan,
            List<OpenCodeClient.SessionAttestation> matches) {
        if (matches == null || matches.stream().anyMatch(match -> match == null
                || match.attestationKind() != OpenCodeClient.SessionAttestationKind.LOCAL_REQUEST_ATTESTED
                || !plan.equals(match.plan()))) return false;
        HashSet<String> ids = new HashSet<>();
        return matches.stream().allMatch(match -> ids.add(match.remoteId()));
    }

    private static OpenCodeClient.OpenCodeSession remote(AcceptanceCandidateInternalLaunchRow launch) {
        return blank(launch.externalSessionId()) ? null : new OpenCodeClient.OpenCodeSession(
                launch.externalSessionId(), Path.of(launch.canonicalDirectory()),
                launch.runtimeGenerationId(), launch.internalMcpServer());
    }

    private static AcceptanceCandidateInternalLaunchState state(AcceptanceCandidateInternalLaunchRow launch) {
        return AcceptanceCandidateInternalLaunchState.valueOf(launch.state());
    }

    private static String code(RuntimeException failure) {
        if (failure instanceof ConflictException conflict) return conflict.code();
        if (failure instanceof io.opencode.loopper.domain.SessionFailure session) return session.code();
        return "OPENCODE_ACCEPTANCE_INTERNAL_LAUNCH_FAILED";
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }

    private CandidateRuntimeBindingService bindings() {
        return bindings.orElseThrow(() -> new ConflictException(
                "CANDIDATE_RUNTIME_BINDING_UNAVAILABLE",
                "内部 MCP 候选运行时绑定服务不可用"));
    }

    enum Status { LEGACY_FALLBACK, PENDING, BLOCKED, CREATED, SETTLED, CLEANUP_PENDING, FAILED_STOPPED }

    record Result(Status status, AcceptanceCandidateInternalLaunchRow launch,
                  OpenCodeClient.OpenCodeSession remote,
                  MachineCandidateSubmission.RunSnapshot run,
                  String code, String detail, String actualToolName) {
        static Result legacy(AcceptanceCandidateInternalLaunchRow row) {
            return new Result(Status.LEGACY_FALLBACK, row, null, null,
                    "OPENCODE_EXACT_LOOKUP_UNSUPPORTED", null, null);
        }
        static Result pending(AcceptanceCandidateInternalLaunchRow row, String code, String detail) {
            return new Result(Status.PENDING, row, null, null, code, detail, null);
        }
        static Result blocked(AcceptanceCandidateInternalLaunchRow row, String code, String detail) {
            return new Result(Status.BLOCKED, row, null, null, code, detail, null);
        }
        static Result created(AcceptanceCandidateInternalLaunchRow row,
                OpenCodeClient.OpenCodeSession remote, String code, String detail) {
            return new Result(Status.CREATED, row, remote, null, code, detail, null);
        }
        static Result settled(AcceptanceCandidateInternalLaunchRow row,
                OpenCodeClient.OpenCodeSession remote, MachineCandidateSubmission.RunSnapshot run) {
            return settled(row, remote, run, null);
        }
        static Result settled(AcceptanceCandidateInternalLaunchRow row,
                OpenCodeClient.OpenCodeSession remote, MachineCandidateSubmission.RunSnapshot run,
                String actualToolName) {
            return new Result(Status.SETTLED, row, remote, run, null, null, actualToolName);
        }
        static Result cleanup(AcceptanceCandidateInternalLaunchRow row, String detail) {
            return new Result(Status.CLEANUP_PENDING, row, null, null,
                    "OPENCODE_SESSION_CREATION_AMBIGUOUS", detail, null);
        }
        static Result failed(AcceptanceCandidateInternalLaunchRow row, String code, String detail) {
            return new Result(Status.FAILED_STOPPED, row, null, null, code, detail, null);
        }
    }
}
