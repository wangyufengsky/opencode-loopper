package io.opencode.loopper.service;

import io.opencode.loopper.domain.AcceptanceCandidateInternalLaunchCleanupState;
import io.opencode.loopper.domain.LifecycleEvent;
import io.opencode.loopper.domain.LifecycleMachineType;
import io.opencode.loopper.domain.LifecycleScopeType;
import io.opencode.loopper.lifecycle.LifecycleTransitionService;
import io.opencode.loopper.persistence.AcceptanceCandidateInternalLaunchCleanupRemoteRow;
import io.opencode.loopper.persistence.AcceptanceCandidateInternalLaunchRow;
import io.opencode.loopper.persistence.LoopperMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Fenced, at-most-once stop ledger for every ambiguous internal-launch remote. */
@Component
class AcceptanceCandidateInternalLaunchCleanupLedger {
    private final LoopperMapper mapper;
    private final LifecycleTransitionService lifecycle;

    AcceptanceCandidateInternalLaunchCleanupLedger(
            LoopperMapper mapper, LifecycleTransitionService lifecycle) {
        this.mapper = mapper;
        this.lifecycle = lifecycle;
    }

    List<AcceptanceCandidateInternalLaunchCleanupRemoteRow> list(String launchId) {
        return mapper.listAcceptanceCandidateInternalLaunchCleanupRemotes(launchId);
    }

    @Transactional
    StopClaim claimStop(String launchId, String remoteId, String claimant, Instant at, Duration ttl) {
        if (blank(claimant) || at == null || ttl == null || ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("Internal launch cleanup claim is incomplete");
        }
        AcceptanceCandidateInternalLaunchCleanupRemoteRow row = require(launchId, remoteId);
        AcceptanceCandidateInternalLaunchCleanupState state = state(row);
        if (state == AcceptanceCandidateInternalLaunchCleanupState.STOPPED) {
            return StopClaim.unavailable(row.stopFence(), "STOPPED");
        }
        if (state == AcceptanceCandidateInternalLaunchCleanupState.STOPPING
                && active(row.stopClaimExpiresAt(), at)) {
            return StopClaim.unavailable(row.stopFence(), "CLAIMED");
        }
        if (state != AcceptanceCandidateInternalLaunchCleanupState.DISCOVERED
                && state != AcceptanceCandidateInternalLaunchCleanupState.DISCONNECTED
                && state != AcceptanceCandidateInternalLaunchCleanupState.STOPPING) throw stale();
        String token = UUID.randomUUID().toString();
        String expiresAt = at.plus(ttl).toString();
        long fence = row.stopFence() + 1;
        int mutation = mapper.claimAcceptanceCandidateInternalLaunchCleanupRemote(
                row.launchId(), row.externalSessionId(), row.version(), row.state(),
                claimant, token, expiresAt, fence, at.toString(), at.toString());
        if (mutation != 1) return StopClaim.unavailable(row.stopFence(), "RACED");
        AcceptanceCandidateInternalLaunchCleanupRemoteRow claimed = require(launchId, remoteId);
        if (state == AcceptanceCandidateInternalLaunchCleanupState.STOPPING) {
            // The SQL mutation already happened in this transaction. Record no duplicate transition.
            return new StopClaim(true, claimant, token, fence, expiresAt, null);
        }
        // claim mapper also performs DISCOVERED/DISCONNECTED -> STOPPING. Persist its audit now;
        // a zero-row no-op keeps the mutation and audit in the same outer transaction.
        lifecycle.transition(subject(claimed), row.state(), claimed.state(), LifecycleEvent.ABORT,
                "ACCEPTANCE_INTERNAL_CLEANUP_STOP_CLAIMED", Map.of(),
                () -> 1, AcceptanceCandidateInternalLaunchCleanupLedger::stale);
        return new StopClaim(true, claimant, token, fence, expiresAt, null);
    }

    @Transactional
    StopCheckpoint markStopDispatchStarted(String launchId, String remoteId, StopClaim claim) {
        AcceptanceCandidateInternalLaunchCleanupRemoteRow row = require(launchId, remoteId);
        requireClaim(row, claim);
        if (row.stopDispatchAttempted()) return new StopCheckpoint(row, false);
        String at = Instant.now().toString();
        lifecycle.mutateWithoutTransition(
                () -> mapper.markAcceptanceCandidateInternalLaunchCleanupStopDispatchStarted(
                        launchId, remoteId, row.version(), claim.owner(), claim.token(), claim.fence(), at, at),
                AcceptanceCandidateInternalLaunchCleanupLedger::stale);
        return new StopCheckpoint(require(launchId, remoteId), true);
    }

    @Transactional
    AcceptanceCandidateInternalLaunchCleanupRemoteRow disconnected(
            String launchId, String remoteId, StopClaim claim, String code, String detail) {
        AcceptanceCandidateInternalLaunchCleanupRemoteRow row = require(launchId, remoteId);
        requireClaim(row, claim);
        AcceptanceCandidateInternalLaunchCleanupRemoteRow update = copy(row,
                AcceptanceCandidateInternalLaunchCleanupState.DISCONNECTED, null, null,
                null, null, null, row.stopFence(), code, safe(detail));
        lifecycle.transition(subject(row), row.state(), update.state(), LifecycleEvent.DISCONNECT,
                "ACCEPTANCE_INTERNAL_CLEANUP_STOP_UNCONFIRMED", Map.of(),
                () -> mapper.updateAcceptanceCandidateInternalLaunchCleanupRemoteAsClaimHolder(
                        update, claim.owner(), claim.token(), claim.fence()),
                AcceptanceCandidateInternalLaunchCleanupLedger::stale);
        return require(launchId, remoteId);
    }

    @Transactional
    AcceptanceCandidateInternalLaunchCleanupRemoteRow stopped(
            String launchId, String remoteId, StopClaim claim, String proof) {
        if (!CandidateSessionTerminationProof.persisted(proof)) {
            throw new ConflictException("ACCEPTANCE_INTERNAL_CLEANUP_STOP_UNCONFIRMED",
                    "内部 MCP 候选清理没有正向停止证明");
        }
        AcceptanceCandidateInternalLaunchCleanupRemoteRow row = require(launchId, remoteId);
        if (row.terminationProof() != null) {
            if (!proof.equals(row.terminationProof())) throw stale();
            return row;
        }
        requireClaim(row, claim);
        String at = Instant.now().toString();
        AcceptanceCandidateInternalLaunchCleanupRemoteRow update = copy(row,
                AcceptanceCandidateInternalLaunchCleanupState.STOPPED, proof, at,
                null, null, null, row.stopFence(), null, null);
        lifecycle.transition(subject(row), row.state(), update.state(), LifecycleEvent.COMPLETE,
                "ACCEPTANCE_INTERNAL_CLEANUP_STOP_CONFIRMED", Map.of(),
                () -> mapper.updateAcceptanceCandidateInternalLaunchCleanupRemoteAsClaimHolder(
                        update, claim.owner(), claim.token(), claim.fence()),
                AcceptanceCandidateInternalLaunchCleanupLedger::stale);
        return require(launchId, remoteId);
    }

    static String entityId(String launchId, String remoteId) { return launchId + ":" + remoteId; }

    private AcceptanceCandidateInternalLaunchCleanupRemoteRow require(String launchId, String remoteId) {
        return list(launchId).stream().filter(row -> remoteId.equals(row.externalSessionId()))
                .findFirst().orElseThrow(AcceptanceCandidateInternalLaunchCleanupLedger::stale);
    }

    private LifecycleTransitionService.Subject subject(
            AcceptanceCandidateInternalLaunchCleanupRemoteRow row) {
        AcceptanceCandidateInternalLaunchRow launch = mapper
                .findAcceptanceCandidateInternalLaunch(row.launchId())
                .orElseThrow(AcceptanceCandidateInternalLaunchCleanupLedger::stale);
        return new LifecycleTransitionService.Subject(
                LifecycleMachineType.ACCEPTANCE_CANDIDATE_INTERNAL_LAUNCH_CLEANUP,
                entityId(row.launchId(), row.externalSessionId()),
                LifecycleScopeType.DESIGNER, launch.designerSessionId());
    }

    private AcceptanceCandidateInternalLaunchCleanupRemoteRow copy(
            AcceptanceCandidateInternalLaunchCleanupRemoteRow row,
            AcceptanceCandidateInternalLaunchCleanupState state, String proof, String proofAt,
            String claimOwner, String claimToken, String claimExpiresAt, long fence,
            String code, String detail) {
        return new AcceptanceCandidateInternalLaunchCleanupRemoteRow(
                row.launchId(), row.externalSessionId(), row.runtimeGenerationId(), row.endpointFingerprint(),
                row.directorySha256(), row.titleSha256(), row.purpose(), row.terminationIntentId(),
                state.name(), proof, proofAt,
                claimOwner, claimToken, claimExpiresAt, fence, row.stopDispatchAttempted(),
                row.stopDispatchStartedAt(), code, detail, row.createdAt(), Instant.now().toString(), row.version());
    }

    private static AcceptanceCandidateInternalLaunchCleanupState state(
            AcceptanceCandidateInternalLaunchCleanupRemoteRow row) {
        try { return AcceptanceCandidateInternalLaunchCleanupState.valueOf(row.state()); }
        catch (RuntimeException invalid) { throw stale(); }
    }

    private static void requireClaim(
            AcceptanceCandidateInternalLaunchCleanupRemoteRow row, StopClaim claim) {
        if (claim == null || !claim.acquired()
                || state(row) != AcceptanceCandidateInternalLaunchCleanupState.STOPPING
                || claim.fence() != row.stopFence()
                || !java.util.Objects.equals(claim.owner(), row.stopClaimOwner())
                || !java.util.Objects.equals(claim.token(), row.stopClaimToken())) throw stale();
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
        return new ConflictException("ACCEPTANCE_INTERNAL_CLEANUP_STALE",
                "内部 MCP 候选清理 ledger 或 fence 已变化");
    }

    record StopClaim(boolean acquired, String owner, String token, long fence,
                     String expiresAt, String reason) {
        static StopClaim unavailable(long fence, String reason) {
            return new StopClaim(false, null, null, fence, null, reason);
        }
    }

    record StopCheckpoint(AcceptanceCandidateInternalLaunchCleanupRemoteRow row,
                          boolean newlyStarted) { }
}
