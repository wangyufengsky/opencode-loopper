package io.opencode.loopper.service;

import io.opencode.loopper.domain.AcceptanceCandidateInternalLaunchCleanupState;
import io.opencode.loopper.domain.AcceptanceCandidateInternalLaunchState;
import io.opencode.loopper.domain.LifecycleEvent;
import io.opencode.loopper.domain.LifecycleMachineType;
import io.opencode.loopper.domain.LifecycleScopeType;
import io.opencode.loopper.lifecycle.LifecycleTransitionService;
import io.opencode.loopper.persistence.AcceptanceCandidateInternalLaunchCleanupRemoteRow;
import io.opencode.loopper.persistence.AcceptanceCandidateInternalLaunchRow;
import io.opencode.loopper.persistence.LoopperMapper;
import io.opencode.loopper.runtime.OpenCodeClient;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Durable, transaction-only half of the Acceptance internal-MCP launch saga. */
@Service
class AcceptanceCandidateInternalLaunchService {
    private final LoopperMapper mapper;
    private final LifecycleTransitionService lifecycle;
    private final AcceptanceCandidateInternalLaunchPlanCodec plans;

    AcceptanceCandidateInternalLaunchService(
            LoopperMapper mapper, LifecycleTransitionService lifecycle,
            AcceptanceCandidateInternalLaunchPlanCodec plans) {
        this.mapper = mapper;
        this.lifecycle = lifecycle;
        this.plans = plans;
    }

    AcceptanceCandidateInternalLaunchRow requireForCompilation(String compilationId) {
        return mapper.findAcceptanceCandidateInternalLaunchForCompilation(compilationId)
                .orElseThrow(AcceptanceCandidateInternalLaunchService::stale);
    }

    AcceptanceCandidateInternalLaunchRow require(String launchId) {
        return mapper.findAcceptanceCandidateInternalLaunch(launchId)
                .orElseThrow(AcceptanceCandidateInternalLaunchService::stale);
    }

    OpenCodeClient.SessionCreationPlan plan(AcceptanceCandidateInternalLaunchRow row) {
        return plans.decode(row);
    }

    List<AcceptanceCandidateInternalLaunchCleanupRemoteRow> cleanup(String launchId) {
        return mapper.listAcceptanceCandidateInternalLaunchCleanupRemotes(launchId);
    }

    @Transactional
    CreateClaim claimCreate(String launchId, String claimant, Instant at, Duration ttl) {
        if (blank(claimant) || at == null || ttl == null || ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("Internal launch create claim is incomplete");
        }
        AcceptanceCandidateInternalLaunchRow row = require(launchId);
        AcceptanceCandidateInternalLaunchState state = state(row);
        if (state != AcceptanceCandidateInternalLaunchState.PREPARED
                && state != AcceptanceCandidateInternalLaunchState.CREATING
                && state != AcceptanceCandidateInternalLaunchState.DISCONNECTED) {
            return CreateClaim.unavailable(row.createFence(), "STATE");
        }
        if (active(row.createClaimExpiresAt(), at)) {
            return CreateClaim.unavailable(row.createFence(), "CLAIMED");
        }
        String token = UUID.randomUUID().toString();
        String expiresAt = at.plus(ttl).toString();
        long fence = row.createFence() + 1;
        lifecycle.mutateWithoutTransition(
                () -> mapper.claimAcceptanceCandidateInternalLaunchCreate(
                        row.id(), row.version(), row.state(), claimant, token, expiresAt,
                        fence, at.toString(), at.toString()),
                AcceptanceCandidateInternalLaunchService::stale);
        return new CreateClaim(true, claimant, token, fence, expiresAt, null);
    }

    @Transactional
    DispatchCheckpoint markCreateDispatchStarted(String launchId, CreateClaim claim) {
        AcceptanceCandidateInternalLaunchRow row = require(launchId);
        requireClaim(row, claim);
        if (row.createDispatchAttempted()) return new DispatchCheckpoint(row, false);
        if (state(row) != AcceptanceCandidateInternalLaunchState.PREPARED) throw stale();
        requireFrozenOwner(row);
        String at = Instant.now().toString();
        lifecycle.transition(subject(row), row.state(), AcceptanceCandidateInternalLaunchState.CREATING.name(),
                LifecycleEvent.DISPATCH, "ACCEPTANCE_INTERNAL_CREATE_POST_RESERVED", Map.of(),
                () -> mapper.markAcceptanceCandidateInternalLaunchCreateDispatchStarted(
                        row.id(), row.version(), claim.owner(), claim.token(), claim.fence(), at, at),
                AcceptanceCandidateInternalLaunchService::stale);
        return new DispatchCheckpoint(require(launchId), true);
    }

    @Transactional
    AcceptanceCandidateInternalLaunchRow releaseCreateClaim(
            String launchId, CreateClaim claim, String phase, String code, String detail) {
        AcceptanceCandidateInternalLaunchRow row = require(launchId);
        requireClaim(row, claim);
        AcceptanceCandidateInternalLaunchRow update = copy(row, row.state(),
                null, null, null, row.createFence(), row.externalSessionId(), row.externalAttestedAt(),
                phase, code, safe(detail));
        lifecycle.mutateWithoutTransition(
                () -> mapper.updateAcceptanceCandidateInternalLaunchAsClaimHolder(
                        update, claim.owner(), claim.token(), claim.fence()),
                AcceptanceCandidateInternalLaunchService::stale);
        return require(launchId);
    }

    @Transactional
    AcceptanceCandidateInternalLaunchRow mechanicalLegacyFallback(
            String launchId, CreateClaim claim) {
        AcceptanceCandidateInternalLaunchRow row = require(launchId);
        requireClaim(row, claim);
        if (state(row) != AcceptanceCandidateInternalLaunchState.PREPARED
                || row.createDispatchAttempted() || row.externalSessionId() != null
                || !cleanup(row.id()).isEmpty()) throw stale();
        AcceptanceCandidateInternalLaunchRow stale = copy(row,
                AcceptanceCandidateInternalLaunchState.STALE.name(),
                null, null, null, row.createFence(), null, null,
                "CREATE_LOOKUP", "OPENCODE_EXACT_LOOKUP_UNSUPPORTED",
                "Exact-title lookup is mechanically unsupported before create dispatch");
        lifecycle.transition(subject(row), row.state(), stale.state(), LifecycleEvent.STALE,
                "ACCEPTANCE_INTERNAL_LOOKUP_UNSUPPORTED", Map.of(),
                () -> mapper.updateAcceptanceCandidateInternalLaunchAsClaimHolder(
                        stale, claim.owner(), claim.token(), claim.fence()),
                AcceptanceCandidateInternalLaunchService::stale);
        return require(launchId);
    }

    @Transactional
    AcceptanceCandidateInternalLaunchRow disconnected(
            String launchId, CreateClaim claim, String phase, String code, String detail) {
        AcceptanceCandidateInternalLaunchRow row = require(launchId);
        requireClaim(row, claim);
        if (state(row) != AcceptanceCandidateInternalLaunchState.CREATING
                && state(row) != AcceptanceCandidateInternalLaunchState.DISCONNECTED) throw stale();
        requireFrozenOwner(row);
        AcceptanceCandidateInternalLaunchRow update = copy(row,
                AcceptanceCandidateInternalLaunchState.DISCONNECTED.name(),
                null, null, null, row.createFence(), row.externalSessionId(), row.externalAttestedAt(),
                phase, code, safe(detail));
        if (row.state().equals(update.state())) {
            lifecycle.mutateWithoutTransition(
                    () -> mapper.updateAcceptanceCandidateInternalLaunchAsClaimHolder(
                            update, claim.owner(), claim.token(), claim.fence()),
                    AcceptanceCandidateInternalLaunchService::stale);
        } else {
            lifecycle.transition(subject(row), row.state(), update.state(), LifecycleEvent.DISCONNECT,
                    "ACCEPTANCE_INTERNAL_CREATE_RESULT_UNKNOWN", Map.of(),
                    () -> mapper.updateAcceptanceCandidateInternalLaunchAsClaimHolder(
                            update, claim.owner(), claim.token(), claim.fence()),
                    AcceptanceCandidateInternalLaunchService::stale);
        }
        return require(launchId);
    }

    @Transactional
    AcceptanceCandidateInternalLaunchRow created(
            String launchId, CreateClaim claim, OpenCodeClient.SessionAttestation attestation) {
        AcceptanceCandidateInternalLaunchRow row = require(launchId);
        requireClaim(row, claim);
        if (attestation == null || !plan(row).equals(attestation.plan())
                || !attestation.managed() || blank(attestation.internalMcpServer())
                || !row.createDispatchAttempted()
                || state(row) != AcceptanceCandidateInternalLaunchState.CREATING
                && state(row) != AcceptanceCandidateInternalLaunchState.DISCONNECTED) throw stale();
        String at = Instant.now().toString();
        AcceptanceCandidateInternalLaunchRow update = copy(row,
                AcceptanceCandidateInternalLaunchState.CREATED.name(),
                row.createClaimOwner(), row.createClaimToken(), row.createClaimExpiresAt(), row.createFence(),
                attestation.remoteId(), at, null, null, null);
        lifecycle.transition(subject(row), row.state(), update.state(), LifecycleEvent.COMPLETE,
                "ACCEPTANCE_INTERNAL_REMOTE_ATTESTED", Map.of("remoteId", attestation.remoteId()),
                () -> mapper.updateAcceptanceCandidateInternalLaunchAsClaimHolder(
                        update, claim.owner(), claim.token(), claim.fence()),
                AcceptanceCandidateInternalLaunchService::stale);
        return require(launchId);
    }

    @Transactional
    List<AcceptanceCandidateInternalLaunchCleanupRemoteRow> registerAmbiguity(
            String launchId, CreateClaim claim, List<OpenCodeClient.SessionAttestation> matches) {
        if (matches == null || matches.size() < 2) throw stale();
        return registerCleanup(launchId, claim, matches, "CREATE_LOOKUP",
                "OPENCODE_SESSION_CREATION_AMBIGUOUS",
                "Exact launch identity matched multiple remote Sessions");
    }

    @Transactional
    List<AcceptanceCandidateInternalLaunchCleanupRemoteRow> registerCleanup(
            String launchId, CreateClaim claim, List<OpenCodeClient.SessionAttestation> matches,
            String phase, String code, String detail) {
        if (matches == null || matches.isEmpty()) throw stale();
        AcceptanceCandidateInternalLaunchRow row = require(launchId);
        requireClaim(row, claim);
        if (!cleanup(row.id()).isEmpty()) return cleanup(row.id());
        OpenCodeClient.SessionCreationPlan plan = plan(row);
        if (matches.stream().anyMatch(match -> match == null || !plan.equals(match.plan()))) throw stale();
        AcceptanceCandidateInternalLaunchRow stopping = copy(row,
                AcceptanceCandidateInternalLaunchState.STOPPING.name(),
                null, null, null, row.createFence(), row.externalSessionId(), row.externalAttestedAt(),
                phase, code, safe(detail));
        lifecycle.transition(subject(row), row.state(), stopping.state(), LifecycleEvent.ABORT,
                "ACCEPTANCE_INTERNAL_AMBIGUOUS_REMOTES", Map.of("remoteCount", matches.size()),
                () -> mapper.updateAcceptanceCandidateInternalLaunchAsClaimHolder(
                        stopping, claim.owner(), claim.token(), claim.fence()),
                AcceptanceCandidateInternalLaunchService::stale);
        String at = Instant.now().toString();
        for (OpenCodeClient.SessionAttestation match : matches) {
            AcceptanceCandidateInternalLaunchCleanupRemoteRow cleanup =
                    new AcceptanceCandidateInternalLaunchCleanupRemoteRow(
                            row.id(), match.remoteId(), match.runtimeGenerationId(), match.endpointFingerprint(),
                            sha256(match.canonicalDirectory().toString()), sha256(match.exactTitle()),
                            "LAUNCH_AMBIGUITY", null,
                            AcceptanceCandidateInternalLaunchCleanupState.DISCOVERED.name(), null, null,
                            null, null, null, 0, false, null, null, null, at, at, 0);
            lifecycle.create(cleanupSubject(row, cleanup), cleanup.state(), Map.of(),
                    () -> mapper.insertAcceptanceCandidateInternalLaunchCleanupRemote(cleanup),
                    AcceptanceCandidateInternalLaunchService::stale);
        }
        return cleanup(row.id());
    }

    @Transactional
    AcceptanceCandidateInternalLaunchRow finishAfterCleanup(String launchId) {
        AcceptanceCandidateInternalLaunchRow row = require(launchId);
        List<AcceptanceCandidateInternalLaunchCleanupRemoteRow> cleanup = cleanup(launchId);
        if (state(row) != AcceptanceCandidateInternalLaunchState.STOPPING || cleanup.isEmpty()
                || cleanup.stream().anyMatch(item -> !AcceptanceCandidateInternalLaunchCleanupState.STOPPED
                        .name().equals(item.state()))) return row;
        String at = Instant.now().toString();
        lifecycle.transition(subject(row), row.state(),
                AcceptanceCandidateInternalLaunchState.FAILED_STOPPED.name(), LifecycleEvent.FAIL,
                "ACCEPTANCE_INTERNAL_AMBIGUITY_STOPPED", Map.of("remoteCount", cleanup.size()),
                () -> mapper.finishAcceptanceCandidateInternalLaunchAfterCleanup(
                        row.id(), row.version(), "OPENCODE_SESSION_CREATION_AMBIGUOUS",
                        "All ambiguous exact-title Sessions were positively stopped", at),
                AcceptanceCandidateInternalLaunchService::stale);
        return require(launchId);
    }

    private AcceptanceCandidateInternalLaunchRow copy(
            AcceptanceCandidateInternalLaunchRow row, String state,
            String claimOwner, String claimToken, String claimExpiresAt, long claimFence,
            String externalSessionId, String externalAttestedAt,
            String failurePhase, String errorCode, String errorDetail) {
        return new AcceptanceCandidateInternalLaunchRow(
                row.id(), row.compilationId(), row.designerSessionId(), row.workPackageId(),
                row.sourceDesignRevision(), row.sourceDesignMessageId(), row.sourceDraftVersion(),
                row.sourceDesignSha256(), row.planningVersion(), row.planningBindingSource(),
                row.planningBindingJson(), row.planningBindingSha256(), row.routePlanJson(),
                row.routePlanSha256(), row.candidateRunId(), row.contractVersion(), row.workflowStep(), state,
                row.preparedOwnerVersion(), row.settledOwnerVersion(), row.settledAt(), row.exactTitle(),
                row.canonicalDirectory(), row.runtimeGenerationId(), row.managed(), row.internalMcpServer(),
                row.endpointFingerprint(), row.modelProviderId(), row.modelId(), row.thinking(), row.profile(),
                row.permissionPolicyJson(), row.permissionPolicyDigest(), row.createRequestSha256(),
                row.creationCredential(), row.attestationType(), claimOwner, claimToken, claimExpiresAt,
                claimFence, row.createDispatchAttempted(), row.createDispatchStartedAt(), externalSessionId,
                externalAttestedAt, row.terminationProof(), row.proofAt(), failurePhase, errorCode, errorDetail,
                row.createdAt(), Instant.now().toString(), row.version());
    }

    private void requireClaim(AcceptanceCandidateInternalLaunchRow row, CreateClaim claim) {
        if (claim == null || !claim.acquired() || claim.fence() != row.createFence()
                || !java.util.Objects.equals(claim.owner(), row.createClaimOwner())
                || !java.util.Objects.equals(claim.token(), row.createClaimToken())) throw stale();
    }

    private void requireFrozenOwner(AcceptanceCandidateInternalLaunchRow row) {
        var owner = mapper.findLoopSpecCompilation(row.compilationId())
                .orElseThrow(AcceptanceCandidateInternalLaunchService::stale);
        if (!"PENDING_HANDOFF".equals(owner.state())
                || owner.version() != row.preparedOwnerVersion()
                || owner.externalSessionId() != null
                || !row.designerSessionId().equals(owner.designerSessionId())
                || !row.workPackageId().equals(owner.workPackageId())
                || owner.designRevision() != row.sourceDesignRevision()
                || !row.sourceDesignMessageId().equals(owner.sourceDesignMessageId())
                || owner.sourceDraftVersion() != row.sourceDraftVersion()) throw stale();
    }

    private LifecycleTransitionService.Subject subject(AcceptanceCandidateInternalLaunchRow row) {
        return new LifecycleTransitionService.Subject(
                LifecycleMachineType.ACCEPTANCE_CANDIDATE_INTERNAL_LAUNCH,
                row.id(), LifecycleScopeType.DESIGNER, row.designerSessionId());
    }

    private LifecycleTransitionService.Subject cleanupSubject(AcceptanceCandidateInternalLaunchRow launch,
            AcceptanceCandidateInternalLaunchCleanupRemoteRow cleanup) {
        return new LifecycleTransitionService.Subject(
                LifecycleMachineType.ACCEPTANCE_CANDIDATE_INTERNAL_LAUNCH_CLEANUP,
                AcceptanceCandidateInternalLaunchCleanupLedger.entityId(
                        cleanup.launchId(), cleanup.externalSessionId()),
                LifecycleScopeType.DESIGNER, launch.designerSessionId());
    }

    private static AcceptanceCandidateInternalLaunchState state(AcceptanceCandidateInternalLaunchRow row) {
        try { return AcceptanceCandidateInternalLaunchState.valueOf(row.state()); }
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
        return new ConflictException("ACCEPTANCE_INTERNAL_LAUNCH_STALE",
                "验收内部 MCP 启动计划、claim 或远端证明已经变化");
    }

    record CreateClaim(boolean acquired, String owner, String token, long fence,
                       String expiresAt, String reason) {
        static CreateClaim unavailable(long fence, String reason) {
            return new CreateClaim(false, null, null, fence, null, reason);
        }
    }

    record DispatchCheckpoint(AcceptanceCandidateInternalLaunchRow row, boolean newlyStarted) { }
}
