package io.opencode.loopper.service;

import io.opencode.loopper.domain.AcceptanceCandidateHandoffCleanupState;
import io.opencode.loopper.domain.LifecycleEvent;
import io.opencode.loopper.domain.LifecycleMachineType;
import io.opencode.loopper.domain.LifecycleScopeType;
import io.opencode.loopper.lifecycle.LifecycleTransitionService;
import io.opencode.loopper.persistence.AcceptanceCandidateHandoffCleanupRemoteRow;
import io.opencode.loopper.persistence.LoopperMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Crash-safe identity and positive-stop ledger for every ambiguous or orphan successor. */
@Component
final class AcceptanceCandidateHandoffCleanupLedger {
    private final LoopperMapper mapper;
    private final AcceptanceCandidateHandoffGuard guard;
    private final LifecycleTransitionService lifecycle;

    AcceptanceCandidateHandoffCleanupLedger(LoopperMapper mapper, AcceptanceCandidateHandoffGuard guard,
            LifecycleTransitionService lifecycle) {
        this.mapper = mapper;
        this.guard = guard;
        this.lifecycle = lifecycle;
    }

    List<AcceptanceCandidateHandoffCleanupRemoteRow> list(String handoffId) {
        return mapper.listAcceptanceCandidateHandoffCleanupRemotes(handoffId);
    }

    void register(String handoffId, List<AcceptanceCandidateLegacyHandoffService.RemoteIdentity> matches) {
        Map<String, AcceptanceCandidateHandoffCleanupRemoteRow> existing = list(handoffId).stream()
                .collect(java.util.stream.Collectors.toMap(
                        AcceptanceCandidateHandoffCleanupRemoteRow::externalSessionId, value -> value));
        String at = Instant.now().toString();
        for (var match : matches) {
            guard.binding(match.externalSessionId(), match.runtimeGenerationId(), match.endpointFingerprint());
            AcceptanceCandidateHandoffCleanupRemoteRow present = existing.get(match.externalSessionId());
            if (present != null) {
                if (!present.runtimeGenerationId().equals(match.runtimeGenerationId())
                        || !present.endpointFingerprint().equals(match.endpointFingerprint())
                        || !present.directorySha256().equals(match.directorySha256())
                        || !present.titleSha256().equals(match.titleSha256())) {
                    throw stale();
                }
                continue;
            }
            AcceptanceCandidateHandoffCleanupRemoteRow created =
                    new AcceptanceCandidateHandoffCleanupRemoteRow(
                    handoffId, match.externalSessionId(), match.runtimeGenerationId(), match.endpointFingerprint(),
                    match.directorySha256(), match.titleSha256(), "DISCOVERED", null, null,
                    null, null, null, 0, null, null,
                    at, at, 0);
            lifecycle.create(subject(created), AcceptanceCandidateHandoffCleanupState.DISCOVERED.name(), Map.of(),
                    () -> mapper.insertAcceptanceCandidateHandoffCleanupRemote(created),
                    AcceptanceCandidateHandoffCleanupLedger::stale);
        }
    }

    StopClaim claimStop(String handoffId, String remoteId, String claimant, Instant instant, Duration ttl) {
        if (claimant == null || claimant.isBlank() || instant == null || ttl == null
                || ttl.isZero() || ttl.isNegative()) throw new IllegalArgumentException("cleanup claim is incomplete");
        AcceptanceCandidateHandoffCleanupRemoteRow row = get(handoffId, remoteId);
        AcceptanceCandidateHandoffCleanupState state = state(row);
        if (state == AcceptanceCandidateHandoffCleanupState.STOPPED) {
            return StopClaim.unavailable(row.stopFence(), "STOPPED");
        }
        if (state == AcceptanceCandidateHandoffCleanupState.STOPPING && active(row.stopClaimExpiresAt(), instant)) {
            return StopClaim.unavailable(row.stopFence(), "CLAIMED");
        }
        if (state != AcceptanceCandidateHandoffCleanupState.DISCOVERED
                && state != AcceptanceCandidateHandoffCleanupState.DISCONNECTED
                && state != AcceptanceCandidateHandoffCleanupState.STOPPING) throw stale();
        String token = UUID.randomUUID().toString();
        String expiresAt = instant.plus(ttl).toString();
        long fence = row.stopFence() + 1;
        AcceptanceCandidateHandoffCleanupRemoteRow update = claimed(
                row, claimant, token, expiresAt, fence, instant.toString());
        try {
            if (state == AcceptanceCandidateHandoffCleanupState.STOPPING) {
                lifecycle.mutateWithoutTransition(() -> claim(row, update, instant),
                        AcceptanceCandidateHandoffCleanupLedger::stale);
            } else {
                lifecycle.transition(subject(row), row.state(), update.state(), LifecycleEvent.ABORT,
                        "OPENCODE_ACCEPTANCE_LEGACY_CLEANUP_STOP_REQUESTED", Map.of(),
                        () -> claim(row, update, instant), AcceptanceCandidateHandoffCleanupLedger::stale);
            }
        } catch (ConflictException raced) {
            AcceptanceCandidateHandoffCleanupRemoteRow current = get(handoffId, remoteId);
            return StopClaim.unavailable(current.stopFence(), "RACED");
        } catch (RuntimeException contention) {
            if (sqliteContention(contention)) return StopClaim.unavailable(row.stopFence(), "CONTENDED");
            throw contention;
        }
        return new StopClaim(true, claimant, token, fence, expiresAt, null);
    }

    AcceptanceCandidateHandoffCleanupRemoteRow disconnected(
            String handoffId, String remoteId, StopClaim claim, String code, String detail) {
        AcceptanceCandidateHandoffCleanupRemoteRow row = get(handoffId, remoteId);
        AcceptanceCandidateHandoffCleanupState state = state(row);
        if (state != AcceptanceCandidateHandoffCleanupState.STOPPING) throw stale();
        requireHolder(row, claim);
        AcceptanceCandidateHandoffCleanupRemoteRow update = released(row,
                AcceptanceCandidateHandoffCleanupState.DISCONNECTED, null, null, code, safe(detail));
        transitionHolder(row, update, claim, LifecycleEvent.DISCONNECT,
                "OPENCODE_ACCEPTANCE_LEGACY_STOP_UNCONFIRMED");
        return get(handoffId, remoteId);
    }

    AcceptanceCandidateHandoffCleanupRemoteRow stopped(
            String handoffId, String remoteId, StopClaim claim, String proof) {
        if (!CandidateSessionTerminationProof.persisted(proof)) {
            throw new ConflictException("OPENCODE_ACCEPTANCE_LEGACY_STOP_UNCONFIRMED",
                    "兼容候选远端没有正向停止证明");
        }
        AcceptanceCandidateHandoffCleanupRemoteRow row = get(handoffId, remoteId);
        if (row.terminationProof() != null) {
            if (!proof.equals(row.terminationProof())) throw stale();
            return row;
        }
        if (state(row) != AcceptanceCandidateHandoffCleanupState.STOPPING) throw stale();
        requireHolder(row, claim);
        String at = Instant.now().toString();
        AcceptanceCandidateHandoffCleanupRemoteRow update = released(row,
                AcceptanceCandidateHandoffCleanupState.STOPPED, proof, at, null, null);
        transitionHolder(row, update, claim, LifecycleEvent.COMPLETE,
                "OPENCODE_ACCEPTANCE_LEGACY_STOP_CONFIRMED");
        return get(handoffId, remoteId);
    }

    static String entityId(String handoffId, String remoteId) {
        return handoffId + ":" + remoteId;
    }

    private AcceptanceCandidateHandoffCleanupRemoteRow get(String handoffId, String remoteId) {
        return list(handoffId).stream().filter(candidate -> remoteId.equals(candidate.externalSessionId()))
                .findFirst().orElseThrow(AcceptanceCandidateHandoffCleanupLedger::stale);
    }

    private AcceptanceCandidateHandoffCleanupState state(AcceptanceCandidateHandoffCleanupRemoteRow row) {
        try {
            return AcceptanceCandidateHandoffCleanupState.valueOf(row.state());
        } catch (IllegalArgumentException invalid) {
            throw stale();
        }
    }

    private AcceptanceCandidateHandoffCleanupRemoteRow claimed(
            AcceptanceCandidateHandoffCleanupRemoteRow row, String owner, String token,
            String expiresAt, long fence, String updatedAt) {
        return new AcceptanceCandidateHandoffCleanupRemoteRow(
                row.handoffId(), row.externalSessionId(), row.runtimeGenerationId(), row.endpointFingerprint(),
                row.directorySha256(), row.titleSha256(), AcceptanceCandidateHandoffCleanupState.STOPPING.name(),
                null, null, owner, token, expiresAt, fence, null, null,
                row.createdAt(), updatedAt, row.version());
    }

    private AcceptanceCandidateHandoffCleanupRemoteRow released(
            AcceptanceCandidateHandoffCleanupRemoteRow row,
            AcceptanceCandidateHandoffCleanupState state, String proof, String proofAt,
            String errorCode, String errorDetail) {
        return new AcceptanceCandidateHandoffCleanupRemoteRow(
                row.handoffId(), row.externalSessionId(), row.runtimeGenerationId(), row.endpointFingerprint(),
                row.directorySha256(), row.titleSha256(), state.name(), proof, proofAt,
                null, null, null, row.stopFence(), errorCode, errorDetail,
                row.createdAt(), Instant.now().toString(), row.version());
    }

    private int claim(AcceptanceCandidateHandoffCleanupRemoteRow from,
            AcceptanceCandidateHandoffCleanupRemoteRow to, Instant claimedAt) {
        return mapper.claimAcceptanceCandidateHandoffCleanupRemote(
                from.handoffId(), from.externalSessionId(), from.version(), from.state(), to.state(),
                to.stopClaimOwner(), to.stopClaimToken(), to.stopClaimExpiresAt(), to.stopFence(),
                claimedAt.toString(), to.updatedAt());
    }

    private void transitionHolder(AcceptanceCandidateHandoffCleanupRemoteRow from,
            AcceptanceCandidateHandoffCleanupRemoteRow to, StopClaim claim, LifecycleEvent event, String reason) {
        lifecycle.transition(subject(from), from.state(), to.state(), event, reason, Map.of(),
                () -> mapper.updateAcceptanceCandidateHandoffCleanupRemoteAsClaimHolder(
                        to, claim.owner(), claim.token(), claim.fence()),
                AcceptanceCandidateHandoffCleanupLedger::stale);
    }

    private void requireHolder(AcceptanceCandidateHandoffCleanupRemoteRow row, StopClaim claim) {
        if (claim == null || !claim.acquired()
                || !java.util.Objects.equals(row.stopClaimOwner(), claim.owner())
                || !java.util.Objects.equals(row.stopClaimToken(), claim.token())
                || row.stopFence() != claim.fence()) throw stale();
    }

    private LifecycleTransitionService.Subject subject(AcceptanceCandidateHandoffCleanupRemoteRow row) {
        String designerSessionId = mapper.findAcceptanceCandidateLegacyHandoff(row.handoffId())
                .orElseThrow(AcceptanceCandidateHandoffCleanupLedger::stale).designerSessionId();
        return new LifecycleTransitionService.Subject(
                LifecycleMachineType.ACCEPTANCE_CANDIDATE_HANDOFF_CLEANUP,
                entityId(row.handoffId(), row.externalSessionId()),
                LifecycleScopeType.DESIGNER, designerSessionId);
    }

    private static String safe(String value) {
        if (value == null) return null;
        return value.length() <= 2_000 ? value : value.substring(0, 2_000);
    }

    private static boolean active(String expiresAt, Instant instant) {
        if (expiresAt == null) return false;
        try {
            return Instant.parse(expiresAt).isAfter(instant);
        } catch (java.time.format.DateTimeParseException invalid) {
            throw stale();
        }
    }

    private static boolean sqliteContention(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            String message = current.getMessage();
            if (message != null && (message.contains("SQLITE_BUSY") || message.contains("SQLITE_LOCKED"))) {
                return true;
            }
        }
        return false;
    }

    private static ConflictException stale() {
        return new ConflictException("ACCEPTANCE_LEGACY_HANDOFF_AMBIGUITY_STALE",
                "兼容候选清理 ledger 已变化");
    }


    record StopClaim(boolean acquired, String owner, String token, long fence, String expiresAt, String reason) {
        static StopClaim unavailable(long fence, String reason) {
            return new StopClaim(false, null, null, fence, null, reason);
        }
    }
}
