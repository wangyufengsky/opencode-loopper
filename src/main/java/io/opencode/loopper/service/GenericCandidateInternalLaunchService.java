package io.opencode.loopper.service;

import io.opencode.loopper.domain.GenericCandidateInternalLaunchCleanupState;
import io.opencode.loopper.domain.GenericCandidateInternalLaunchState;
import io.opencode.loopper.domain.LifecycleEvent;
import io.opencode.loopper.domain.LifecycleMachineType;
import io.opencode.loopper.domain.LifecycleScopeType;
import io.opencode.loopper.lifecycle.LifecycleTransitionService;
import io.opencode.loopper.persistence.GenericCandidateInternalLaunchCleanupRemoteRow;
import io.opencode.loopper.persistence.GenericCandidateInternalLaunchRow;
import io.opencode.loopper.persistence.LoopperMapper;
import io.opencode.loopper.runtime.OpenCodeClient;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Transaction-only half of the recoverable V57 generic internal-launch saga. */
@Service
class GenericCandidateInternalLaunchService {
    private final LoopperMapper mapper;
    private final LifecycleTransitionService lifecycle;
    private final GenericCandidateInternalLaunchPlanCodec plans;

    GenericCandidateInternalLaunchService(
            LoopperMapper mapper, LifecycleTransitionService lifecycle,
            GenericCandidateInternalLaunchPlanCodec plans) {
        this.mapper = mapper;
        this.lifecycle = lifecycle;
        this.plans = plans;
    }

    GenericCandidateInternalLaunchRow require(String launchId) {
        return mapper.findGenericCandidateInternalLaunch(launchId)
                .orElseThrow(GenericCandidateInternalLaunchService::stale);
    }

    GenericCandidateInternalLaunchRow requireForRun(String runId) {
        return mapper.findGenericCandidateInternalLaunchForRun(runId)
                .orElseThrow(GenericCandidateInternalLaunchService::stale);
    }

    OpenCodeClient.SessionCreationPlan plan(GenericCandidateInternalLaunchRow row) {
        return plans.decode(row);
    }

    List<GenericCandidateInternalLaunchCleanupRemoteRow> cleanup(String launchId) {
        return mapper.listGenericCandidateInternalLaunchCleanupRemotes(launchId);
    }

    @Transactional
    CreateClaim claimCreate(String launchId, String claimant, Instant at, Duration ttl) {
        if (blank(claimant) || at == null || ttl == null || ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("Generic launch create claim is incomplete");
        }
        GenericCandidateInternalLaunchRow row = require(launchId);
        GenericCandidateInternalLaunchState state = state(row);
        if (state != GenericCandidateInternalLaunchState.PREPARED
                && state != GenericCandidateInternalLaunchState.CREATING
                && state != GenericCandidateInternalLaunchState.DISCONNECTED) {
            return CreateClaim.unavailable(row.createFence(), "STATE");
        }
        if (active(row.createClaimExpiresAt(), at)) {
            return CreateClaim.unavailable(row.createFence(), "CLAIMED");
        }
        String token = UUID.randomUUID().toString();
        String expires = at.plus(ttl).toString();
        long fence = row.createFence() + 1;
        lifecycle.mutateWithoutTransition(
                () -> mapper.claimGenericCandidateInternalLaunchCreate(
                        row.id(), row.version(), row.state(), claimant, token, expires,
                        fence, at.toString(), at.toString()),
                GenericCandidateInternalLaunchService::stale);
        return new CreateClaim(true, claimant, token, fence, expires, null);
    }

    @Transactional
    DispatchCheckpoint markCreateDispatchStarted(String launchId, CreateClaim claim) {
        GenericCandidateInternalLaunchRow row = require(launchId);
        requireClaim(row, claim);
        if (row.createDispatchAttempted()) return new DispatchCheckpoint(row, false);
        if (state(row) != GenericCandidateInternalLaunchState.PREPARED) throw stale();
        requireFrozenOwner(row);
        String at = Instant.now().toString();
        lifecycle.transition(subject(row), row.state(), GenericCandidateInternalLaunchState.CREATING.name(),
                LifecycleEvent.DISPATCH, "GENERIC_CANDIDATE_INTERNAL_CREATE_POST_RESERVED", Map.of(),
                () -> mapper.markGenericCandidateInternalLaunchCreateDispatchStarted(
                        row.id(), row.version(), claim.owner(), claim.token(), claim.fence(), at, at),
                GenericCandidateInternalLaunchService::stale);
        return new DispatchCheckpoint(require(launchId), true);
    }

    @Transactional
    GenericCandidateInternalLaunchRow releaseCreateClaim(
            String launchId, CreateClaim claim, String phase, String code, String detail) {
        GenericCandidateInternalLaunchRow row = require(launchId);
        requireClaim(row, claim);
        GenericCandidateInternalLaunchRow update = copy(row, row.state(), null, null, null,
                row.externalSessionId(), row.externalAttestedAt(), phase, code, safe(detail));
        lifecycle.mutateWithoutTransition(
                () -> mapper.updateGenericCandidateInternalLaunchAsClaimHolder(
                        update, claim.owner(), claim.token(), claim.fence()),
                GenericCandidateInternalLaunchService::stale);
        return require(launchId);
    }

    @Transactional
    GenericCandidateInternalLaunchRow disconnected(
            String launchId, CreateClaim claim, String phase, String code, String detail) {
        GenericCandidateInternalLaunchRow row = require(launchId);
        requireClaim(row, claim);
        GenericCandidateInternalLaunchState current = state(row);
        if (current != GenericCandidateInternalLaunchState.CREATING
                && current != GenericCandidateInternalLaunchState.DISCONNECTED) throw stale();
        GenericCandidateInternalLaunchRow update = copy(row,
                GenericCandidateInternalLaunchState.DISCONNECTED.name(), null, null, null,
                row.externalSessionId(), row.externalAttestedAt(), phase, code, safe(detail));
        if (current == GenericCandidateInternalLaunchState.DISCONNECTED) {
            lifecycle.mutateWithoutTransition(
                    () -> mapper.updateGenericCandidateInternalLaunchAsClaimHolder(
                            update, claim.owner(), claim.token(), claim.fence()),
                    GenericCandidateInternalLaunchService::stale);
        } else {
            lifecycle.transition(subject(row), row.state(), update.state(), LifecycleEvent.DISCONNECT,
                    "GENERIC_CANDIDATE_INTERNAL_CREATE_UNKNOWN", Map.of(),
                    () -> mapper.updateGenericCandidateInternalLaunchAsClaimHolder(
                            update, claim.owner(), claim.token(), claim.fence()),
                    GenericCandidateInternalLaunchService::stale);
        }
        return require(launchId);
    }

    @Transactional
    GenericCandidateInternalLaunchRow created(
            String launchId, CreateClaim claim, OpenCodeClient.SessionAttestation attestation) {
        GenericCandidateInternalLaunchRow row = require(launchId);
        requireClaim(row, claim);
        OpenCodeClient.SessionCreationPlan plan = plans.decode(row);
        if (attestation == null
                || attestation.attestationKind() != OpenCodeClient.SessionAttestationKind.LOCAL_REQUEST_ATTESTED
                || !plan.equals(attestation.plan())) throw stale();
        GenericCandidateInternalLaunchState current = state(row);
        if (current != GenericCandidateInternalLaunchState.CREATING
                && current != GenericCandidateInternalLaunchState.DISCONNECTED) throw stale();
        String at = Instant.now().toString();
        GenericCandidateInternalLaunchRow update = copy(row,
                GenericCandidateInternalLaunchState.CREATED.name(),
                row.createClaimOwner(), row.createClaimToken(), row.createClaimExpiresAt(),
                attestation.remoteId(), at, null, null, null);
        lifecycle.transition(subject(row), row.state(), update.state(), LifecycleEvent.COMPLETE,
                "GENERIC_CANDIDATE_INTERNAL_REMOTE_ATTESTED", Map.of("remoteId", attestation.remoteId()),
                () -> mapper.updateGenericCandidateInternalLaunchAsClaimHolder(
                        update, claim.owner(), claim.token(), claim.fence()),
                GenericCandidateInternalLaunchService::stale);
        return require(launchId);
    }

    @Transactional
    List<GenericCandidateInternalLaunchCleanupRemoteRow> registerAmbiguity(
            String launchId, CreateClaim claim, List<OpenCodeClient.SessionAttestation> remotes) {
        return registerCleanup(launchId, claim, remotes, "LAUNCH_AMBIGUITY",
                "CREATE_LOOKUP", "OPENCODE_SESSION_CREATION_AMBIGUOUS",
                "Exact-title lookup found multiple attested remotes");
    }

    @Transactional
    List<GenericCandidateInternalLaunchCleanupRemoteRow> registerCleanup(
            String launchId, CreateClaim claim, List<OpenCodeClient.SessionAttestation> remotes,
            String purpose, String phase, String code, String detail) {
        GenericCandidateInternalLaunchRow row = require(launchId);
        requireClaim(row, claim);
        if (remotes == null || remotes.isEmpty() || !cleanup(launchId).isEmpty()) throw stale();
        OpenCodeClient.SessionCreationPlan plan = plans.decode(row);
        if (remotes.stream().anyMatch(remote -> remote == null || !plan.equals(remote.plan())
                || remote.attestationKind() != OpenCodeClient.SessionAttestationKind.LOCAL_REQUEST_ATTESTED)) {
            throw stale();
        }
        GenericCandidateInternalLaunchRow stopping = copy(row,
                GenericCandidateInternalLaunchState.STOPPING.name(), null, null, null,
                row.externalSessionId(), row.externalAttestedAt(), phase, code, safe(detail));
        lifecycle.transition(subject(row), row.state(), stopping.state(), LifecycleEvent.ABORT,
                "GENERIC_CANDIDATE_INTERNAL_CLEANUP_REGISTERED", Map.of("remoteCount", remotes.size()),
                () -> mapper.updateGenericCandidateInternalLaunchAsClaimHolder(
                        stopping, claim.owner(), claim.token(), claim.fence()),
                GenericCandidateInternalLaunchService::stale);
        String at = Instant.now().toString();
        for (OpenCodeClient.SessionAttestation remote : remotes) {
            GenericCandidateInternalLaunchCleanupRemoteRow cleanup =
                    new GenericCandidateInternalLaunchCleanupRemoteRow(
                            launchId, remote.remoteId(), remote.runtimeGenerationId(),
                            remote.endpointFingerprint(), sha256(remote.canonicalDirectory().toString()),
                            sha256(remote.exactTitle()), purpose,
                            GenericCandidateInternalLaunchCleanupState.DISCOVERED.name(), null, null,
                            null, null, null, 0, false, null, null, null, at, at, 0);
            lifecycle.create(cleanupSubject(row, cleanup), cleanup.state(), Map.of("purpose", purpose),
                    () -> mapper.insertGenericCandidateInternalLaunchCleanupRemote(cleanup),
                    GenericCandidateInternalLaunchService::stale);
        }
        return cleanup(launchId);
    }

    @Transactional
    GenericCandidateInternalLaunchRow finishAfterCleanup(String launchId) {
        GenericCandidateInternalLaunchRow row = require(launchId);
        List<GenericCandidateInternalLaunchCleanupRemoteRow> remotes = cleanup(launchId);
        if (state(row) != GenericCandidateInternalLaunchState.STOPPING || remotes.isEmpty()
                || remotes.stream().anyMatch(remote ->
                        !GenericCandidateInternalLaunchCleanupState.STOPPED.name().equals(remote.state()))) {
            throw stale();
        }
        String at = Instant.now().toString();
        lifecycle.transition(subject(row), row.state(), GenericCandidateInternalLaunchState.FAILED_STOPPED.name(),
                LifecycleEvent.FAIL, "GENERIC_CANDIDATE_INTERNAL_CLEANUP_COMPLETE", Map.of(),
                () -> mapper.finishGenericCandidateInternalLaunchAfterCleanup(
                        row.id(), row.version(), row.lastErrorCode(), row.lastErrorDetail(), at),
                GenericCandidateInternalLaunchService::stale);
        return require(launchId);
    }

    private void requireFrozenOwner(GenericCandidateInternalLaunchRow row) {
        boolean exact = switch (row.candidateKind()) {
            case "REVIEWER_REPORT_V1" -> mapper.findAnalysisReport(row.designerSessionId(), row.ownerId())
                    .filter(owner -> "RUNNING".equals(owner.state()) && owner.externalSessionId() == null
                            && owner.version() == row.preparedOwnerVersion()).isPresent();
            case "PROJECT_CONVENTION_V1" -> mapper.findProjectConventionDraft(row.ownerId())
                    .filter(owner -> row.projectId().equals(owner.projectId()) && "RUNNING".equals(owner.state())
                            && owner.externalSessionId() == null
                            && owner.version() == row.preparedOwnerVersion()).isPresent();
            case "JUDGE_DECISION_V1" -> mapper.findJudgeRun(row.ownerId())
                    .filter(owner -> row.taskId().equals(owner.taskId()) && "CREATING".equals(owner.state())
                            && owner.externalSessionId() == null
                            && owner.version() == row.preparedOwnerVersion()).isPresent();
            default -> false;
        };
        if (!exact) throw stale();
    }

    private static GenericCandidateInternalLaunchRow copy(
            GenericCandidateInternalLaunchRow row, String state,
            String claimOwner, String claimToken, String claimExpiresAt,
            String externalSessionId, String externalAttestedAt,
            String phase, String code, String detail) {
        return new GenericCandidateInternalLaunchRow(
                row.id(), row.candidateRunId(), row.candidateKind(), row.designerSessionId(), row.taskId(),
                row.projectId(), row.ownerType(), row.ownerId(), row.analysisReportId(),
                row.projectConventionDraftId(), row.judgeRunId(), row.workflowStep(), row.sourceRevision(),
                row.contractVersion(), row.maxAttempts(), state, row.preparedOwnerVersion(),
                row.settledOwnerVersion(), row.settledAt(), row.exactTitle(), row.canonicalDirectory(),
                row.runtimeGenerationId(), row.managed(), row.internalMcpServer(), row.endpointFingerprint(),
                row.modelProviderId(), row.modelId(), row.thinking(), row.profile(), row.permissionPolicyJson(),
                row.permissionPolicyDigest(), row.createRequestSha256(), row.creationCredential(),
                row.attestationType(), claimOwner, claimToken, claimExpiresAt, row.createFence(),
                row.createDispatchAttempted(), row.createDispatchStartedAt(), externalSessionId,
                externalAttestedAt, row.terminationProof(), row.proofAt(), phase, code, detail,
                row.createdAt(), Instant.now().toString(), row.version());
    }

    private static void requireClaim(GenericCandidateInternalLaunchRow row, CreateClaim claim) {
        if (claim == null || !claim.acquired() || !Objects.equals(row.createClaimOwner(), claim.owner())
                || !Objects.equals(row.createClaimToken(), claim.token())
                || row.createFence() != claim.fence()) throw stale();
    }

    private static LifecycleTransitionService.Subject subject(GenericCandidateInternalLaunchRow row) {
        LifecycleScopeType type = row.designerSessionId() != null ? LifecycleScopeType.DESIGNER
                : row.taskId() != null ? LifecycleScopeType.TASK : LifecycleScopeType.PROJECT;
        String scope = row.designerSessionId() != null ? row.designerSessionId()
                : row.taskId() != null ? row.taskId() : row.projectId();
        return new LifecycleTransitionService.Subject(
                LifecycleMachineType.GENERIC_CANDIDATE_INTERNAL_LAUNCH, row.id(), type, scope);
    }

    private static LifecycleTransitionService.Subject cleanupSubject(
            GenericCandidateInternalLaunchRow launch,
            GenericCandidateInternalLaunchCleanupRemoteRow cleanup) {
        LifecycleTransitionService.Subject parent = subject(launch);
        return new LifecycleTransitionService.Subject(
                LifecycleMachineType.GENERIC_CANDIDATE_INTERNAL_LAUNCH_CLEANUP,
                GenericCandidateInternalLaunchCleanupLedger.entityId(
                        cleanup.launchId(), cleanup.externalSessionId()), parent.scopeType(), parent.scopeId());
    }

    private static GenericCandidateInternalLaunchState state(GenericCandidateInternalLaunchRow row) {
        try { return GenericCandidateInternalLaunchState.valueOf(row.state()); }
        catch (RuntimeException invalid) { throw stale(); }
    }

    private static boolean active(String expiresAt, Instant at) {
        if (expiresAt == null) return false;
        try { return Instant.parse(expiresAt).isAfter(at); }
        catch (RuntimeException invalid) { throw stale(); }
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception impossible) { throw new IllegalStateException(impossible); }
    }

    private static String safe(String value) {
        if (value == null) return null;
        return value.length() <= 2_000 ? value : value.substring(0, 2_000);
    }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static ConflictException stale() {
        return new ConflictException("GENERIC_CANDIDATE_INTERNAL_LAUNCH_STALE",
                "通用候选 internal launch、owner、claim 或远端证明已变化");
    }

    record CreateClaim(boolean acquired, String owner, String token, long fence,
                       String expiresAt, String reason) {
        static CreateClaim unavailable(long fence, String reason) {
            return new CreateClaim(false, null, null, fence, null, reason);
        }
    }
    record DispatchCheckpoint(GenericCandidateInternalLaunchRow row, boolean newlyStarted) { }
}
