package io.opencode.loopper.service;

import io.opencode.loopper.domain.GenericCandidateInternalLaunchCleanupState;
import io.opencode.loopper.domain.LifecycleEvent;
import io.opencode.loopper.domain.LifecycleMachineType;
import io.opencode.loopper.domain.LifecycleScopeType;
import io.opencode.loopper.lifecycle.LifecycleTransitionService;
import io.opencode.loopper.persistence.GenericCandidateInternalLaunchCleanupRemoteRow;
import io.opencode.loopper.persistence.GenericCandidateInternalLaunchRow;
import io.opencode.loopper.persistence.LoopperMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Fenced at-most-once stop-dispatch ledger for V57 cleanup remotes. */
@Service
class GenericCandidateInternalLaunchCleanupLedger {
    private final LoopperMapper mapper;
    private final LifecycleTransitionService lifecycle;

    GenericCandidateInternalLaunchCleanupLedger(LoopperMapper mapper, LifecycleTransitionService lifecycle) {
        this.mapper = mapper;
        this.lifecycle = lifecycle;
    }

    List<GenericCandidateInternalLaunchCleanupRemoteRow> list(String launchId) {
        return mapper.listGenericCandidateInternalLaunchCleanupRemotes(launchId);
    }

    @Transactional
    StopClaim claimStop(String launchId, String remoteId, String claimant, Instant at, Duration ttl) {
        if (blank(claimant) || at == null || ttl == null || ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("Generic cleanup stop claim is incomplete");
        }
        GenericCandidateInternalLaunchCleanupRemoteRow row = require(launchId, remoteId);
        GenericCandidateInternalLaunchCleanupState state = state(row);
        if (state == GenericCandidateInternalLaunchCleanupState.STOPPED) {
            return StopClaim.unavailable(row.stopFence(), "STOPPED");
        }
        if (active(row.stopClaimExpiresAt(), at)) return StopClaim.unavailable(row.stopFence(), "CLAIMED");
        if (state != GenericCandidateInternalLaunchCleanupState.DISCOVERED
                && state != GenericCandidateInternalLaunchCleanupState.STOPPING
                && state != GenericCandidateInternalLaunchCleanupState.DISCONNECTED) {
            return StopClaim.unavailable(row.stopFence(), "STATE");
        }
        String token = UUID.randomUUID().toString();
        String expires = at.plus(ttl).toString();
        long fence = row.stopFence() + 1;
        lifecycle.transition(subject(launchId, row), row.state(),
                GenericCandidateInternalLaunchCleanupState.STOPPING.name(), LifecycleEvent.ABORT,
                "GENERIC_CANDIDATE_INTERNAL_STOP_CLAIMED", Map.of(),
                () -> mapper.claimGenericCandidateInternalLaunchCleanupRemote(
                        launchId, remoteId, row.version(), row.state(), claimant, token,
                        expires, fence, at.toString(), at.toString()),
                GenericCandidateInternalLaunchCleanupLedger::stale);
        return new StopClaim(true, claimant, token, fence, expires, null);
    }

    @Transactional
    StopCheckpoint markStopDispatchStarted(String launchId, String remoteId, StopClaim claim) {
        GenericCandidateInternalLaunchCleanupRemoteRow row = require(launchId, remoteId);
        requireClaim(row, claim);
        if (row.stopDispatchAttempted()) return new StopCheckpoint(row, false);
        String at = Instant.now().toString();
        lifecycle.mutateWithoutTransition(
                () -> mapper.markGenericCandidateInternalLaunchCleanupStopDispatchStarted(
                        launchId, remoteId, row.version(), claim.owner(), claim.token(),
                        claim.fence(), at, at), GenericCandidateInternalLaunchCleanupLedger::stale);
        return new StopCheckpoint(require(launchId, remoteId), true);
    }

    @Transactional
    GenericCandidateInternalLaunchCleanupRemoteRow stopped(
            String launchId, String remoteId, StopClaim claim, String proof) {
        if (!CandidateSessionTerminationProof.persisted(proof)) throw stale();
        GenericCandidateInternalLaunchCleanupRemoteRow row = require(launchId, remoteId);
        requireClaim(row, claim);
        String at = Instant.now().toString();
        GenericCandidateInternalLaunchCleanupRemoteRow update = copy(row,
                GenericCandidateInternalLaunchCleanupState.STOPPED.name(), proof, at,
                null, null, null, null, null);
        lifecycle.transition(subject(launchId, row), row.state(), update.state(), LifecycleEvent.COMPLETE,
                "GENERIC_CANDIDATE_INTERNAL_STOP_CONFIRMED", Map.of("proof", proof),
                () -> mapper.updateGenericCandidateInternalLaunchCleanupRemoteAsClaimHolder(
                        update, claim.owner(), claim.token(), claim.fence()),
                GenericCandidateInternalLaunchCleanupLedger::stale);
        return require(launchId, remoteId);
    }

    @Transactional
    GenericCandidateInternalLaunchCleanupRemoteRow disconnected(
            String launchId, String remoteId, StopClaim claim, String code, String detail) {
        GenericCandidateInternalLaunchCleanupRemoteRow row = require(launchId, remoteId);
        requireClaim(row, claim);
        GenericCandidateInternalLaunchCleanupRemoteRow update = copy(row,
                GenericCandidateInternalLaunchCleanupState.DISCONNECTED.name(), null, null,
                null, null, safe(code), safe(detail), null);
        lifecycle.transition(subject(launchId, row), row.state(), update.state(), LifecycleEvent.DISCONNECT,
                "GENERIC_CANDIDATE_INTERNAL_STOP_UNKNOWN", Map.of(),
                () -> mapper.updateGenericCandidateInternalLaunchCleanupRemoteAsClaimHolder(
                        update, claim.owner(), claim.token(), claim.fence()),
                GenericCandidateInternalLaunchCleanupLedger::stale);
        return require(launchId, remoteId);
    }

    private GenericCandidateInternalLaunchCleanupRemoteRow require(String launchId, String remoteId) {
        return list(launchId).stream().filter(row -> remoteId.equals(row.externalSessionId()))
                .findFirst().orElseThrow(GenericCandidateInternalLaunchCleanupLedger::stale);
    }

    private LifecycleTransitionService.Subject subject(
            String launchId, GenericCandidateInternalLaunchCleanupRemoteRow row) {
        GenericCandidateInternalLaunchRow launch = mapper.findGenericCandidateInternalLaunch(launchId)
                .orElseThrow(GenericCandidateInternalLaunchCleanupLedger::stale);
        LifecycleScopeType type = launch.designerSessionId() != null ? LifecycleScopeType.DESIGNER
                : launch.taskId() != null ? LifecycleScopeType.TASK : LifecycleScopeType.PROJECT;
        String scope = launch.designerSessionId() != null ? launch.designerSessionId()
                : launch.taskId() != null ? launch.taskId() : launch.projectId();
        return new LifecycleTransitionService.Subject(
                LifecycleMachineType.GENERIC_CANDIDATE_INTERNAL_LAUNCH_CLEANUP,
                entityId(launchId, row.externalSessionId()), type, scope);
    }

    static String entityId(String launchId, String remoteId) { return launchId + "::" + remoteId; }

    private static GenericCandidateInternalLaunchCleanupRemoteRow copy(
            GenericCandidateInternalLaunchCleanupRemoteRow row, String state, String proof, String proofAt,
            String claimOwner, String claimToken, String code, String detail, String unused) {
        return new GenericCandidateInternalLaunchCleanupRemoteRow(
                row.launchId(), row.externalSessionId(), row.runtimeGenerationId(), row.endpointFingerprint(),
                row.directorySha256(), row.titleSha256(), row.purpose(), state, proof, proofAt,
                claimOwner, claimToken, null, row.stopFence(), row.stopDispatchAttempted(),
                row.stopDispatchStartedAt(), code, detail, row.createdAt(), Instant.now().toString(), row.version());
    }

    private static void requireClaim(GenericCandidateInternalLaunchCleanupRemoteRow row, StopClaim claim) {
        if (claim == null || !claim.acquired() || !Objects.equals(row.stopClaimOwner(), claim.owner())
                || !Objects.equals(row.stopClaimToken(), claim.token())
                || row.stopFence() != claim.fence()) throw stale();
    }
    private static GenericCandidateInternalLaunchCleanupState state(
            GenericCandidateInternalLaunchCleanupRemoteRow row) {
        try { return GenericCandidateInternalLaunchCleanupState.valueOf(row.state()); }
        catch (RuntimeException invalid) { throw stale(); }
    }
    private static boolean active(String expiresAt, Instant at) {
        if (expiresAt == null) return false;
        try { return Instant.parse(expiresAt).isAfter(at); }
        catch (RuntimeException invalid) { throw stale(); }
    }
    private static String safe(String value) {
        if (value == null) return null;
        return value.length() <= 2_000 ? value : value.substring(0, 2_000);
    }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static ConflictException stale() {
        return new ConflictException("GENERIC_CANDIDATE_INTERNAL_CLEANUP_STALE",
                "通用候选 cleanup remote、claim 或停止证明已变化");
    }

    record StopClaim(boolean acquired, String owner, String token, long fence,
                     String expiresAt, String reason) {
        static StopClaim unavailable(long fence, String reason) {
            return new StopClaim(false, null, null, fence, null, reason);
        }
    }
    record StopCheckpoint(GenericCandidateInternalLaunchCleanupRemoteRow row, boolean newlyStarted) { }
}
