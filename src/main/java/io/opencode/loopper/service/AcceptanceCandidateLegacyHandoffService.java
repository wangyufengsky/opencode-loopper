package io.opencode.loopper.service;
import static io.opencode.loopper.service.AcceptanceCandidateLegacyHandoffRows.copy;
import static io.opencode.loopper.service.AcceptanceCandidateLegacyHandoffRows.copyCompilation;
import static io.opencode.loopper.service.AcceptanceCandidateLegacyHandoffRows.withClaims;
import static io.opencode.loopper.service.AcceptanceCandidateLegacyHandoffRows.withCreateDispatch;
import static io.opencode.loopper.service.AcceptanceCandidateLegacyHandoffRows.withPromptDispatch;
import io.opencode.loopper.domain.AcceptanceCandidateHandoffState;
import io.opencode.loopper.domain.LifecycleEvent;
import io.opencode.loopper.domain.LifecycleMachineType;
import io.opencode.loopper.domain.LifecycleScopeType;
import io.opencode.loopper.lifecycle.LifecycleTransitionService;
import io.opencode.loopper.persistence.AcceptanceCandidateLegacyHandoffRow;
import io.opencode.loopper.persistence.AcceptanceCandidateHandoffCleanupRemoteRow;
import io.opencode.loopper.persistence.DesignAcceptancePlanningRow;
import io.opencode.loopper.persistence.DesignRequirementRevisionRow;
import io.opencode.loopper.persistence.DesignerSessionRow;
import io.opencode.loopper.persistence.LoopSpecCompilationRow;
import io.opencode.loopper.persistence.LoopperMapper;
import io.opencode.loopper.runtime.OpenCodeClient;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
@Service
class AcceptanceCandidateLegacyHandoffService {
    private static final String CONTRACT = "ACCEPTANCE_CLOSED_CHOICE_V7";
    private final LoopperMapper mapper;
    private final LifecycleTransitionService lifecycle;
    private final DesignerAcceptanceCandidateOrchestrator candidates;
    private final AcceptanceCandidateSuccessorPlanCodec plans;
    private final AcceptanceCandidateHandoffGuard guard;
    private final AcceptanceCandidateHandoffCleanupLedger cleanupLedger;
    private final AcceptanceCandidateHandoffTerminationService termination;
    AcceptanceCandidateLegacyHandoffService(LoopperMapper mapper, LifecycleTransitionService lifecycle,
            DesignerAcceptanceCandidateOrchestrator candidates, AcceptanceCandidateSuccessorPlanCodec plans,
            AcceptanceCandidateHandoffGuard guard, AcceptanceCandidateHandoffCleanupLedger cleanupLedger,
            AcceptanceCandidateHandoffTerminationService termination) {
        this.mapper = mapper;
        this.lifecycle = lifecycle;
        this.candidates = candidates;
        this.plans = plans;
        this.guard = guard;
        this.cleanupLedger = cleanupLedger;
        this.termination = termination;
    }
    Optional<AcceptanceCandidateLegacyHandoffRow> find(String compilationId) {
        return mapper.findAcceptanceCandidateLegacyHandoffForCompilation(compilationId); }
    List<AcceptanceCandidateLegacyHandoffRow> activeForDesigner(String designerSessionId) {
        return mapper.listAcceptanceCandidateHandoffsForDesigner(designerSessionId); }
    List<AcceptanceCandidateHandoffCleanupRemoteRow> cleanupRemotes(String handoffId) {
        return cleanupLedger.list(handoffId); }
    OpenCodeClient.SessionCreationPlan creationPlan(AcceptanceCandidateLegacyHandoffRow row) {
        return plans.decode(row); }
    @Transactional
    AcceptanceCandidateLegacyHandoffRow begin(LoopSpecCompilationRow snapshot,
            DesignerSessionRow owner, DesignAcceptancePlanningRow planning,
            OpenCodeClient.SessionCreationPlan successorPlan) {
        AcceptanceCandidateLegacyHandoffRow existing = mapper
                .findAcceptanceCandidateLegacyHandoffForCompilation(snapshot.id()).orElse(null);
        if (existing != null) {
            guard.sameAnchor(existing, snapshot);
            return existing;
        }
        guard.owner(snapshot, owner, snapshot.externalSessionId(), snapshot.version());
        if (planning == null || !DesignerAcceptancePlanning.CONTRACT_VERSION_V7.equals(planning.contractVersion())
                || !snapshot.id().equals(planning.compilationId())) {
            throw new ConflictException("ACCEPTANCE_LEGACY_HANDOFF_PLANNING_STALE",
                    "验收兼容交接的冻结规划已变化");
        }
        var binding = snapshot.externalSessionId() == null ? null : guard.binding(snapshot.externalSessionId());
        plans.validate(owner, successorPlan);
        String source = mapper.findDesignerMessage(snapshot.sourceDesignMessageId())
                .orElseThrow(() -> stale("ACCEPTANCE_LEGACY_HANDOFF_SOURCE_MISSING")).content();
        String id = deterministic("acceptance-v7-durable-handoff:" + snapshot.id() + ":"
                + snapshot.externalSessionId() + ":" + snapshot.version());
        String creationKey = "acceptance-v7-legacy-session:" + id;
        String permissionPolicyJson = plans.encodePermissionPolicy(successorPlan);
        String now = now();
        AcceptanceCandidateLegacyHandoffRow created = new AcceptanceCandidateLegacyHandoffRow(
                id, snapshot.id(), snapshot.designerSessionId(), snapshot.workPackageId(),
                snapshot.designRevision(), snapshot.sourceDesignMessageId(), snapshot.sourceDraftVersion(),
                AcceptanceCandidateHandoffGuard.sha256(source), CONTRACT, snapshot.externalSessionId() == null
                ? AcceptanceCandidateHandoffState.CREATING_LEGACY.name()
                : AcceptanceCandidateHandoffState.STOPPING_OLD.name(),
                snapshot.version(), snapshot.version(), snapshot.externalSessionId(),
                binding == null ? null : binding.runtimeGenerationId(),
                binding == null ? null : binding.endpointFingerprint(),
                snapshot.externalSessionId() == null ? "NOT_CREATED" : snapshot.externalSessionState(),
                null, null, creationKey, successorPlan.exactTitle(), successorPlan.canonicalDirectory().toString(),
                successorPlan.runtimeGenerationId(), successorPlan.managed(), successorPlan.internalMcpServer(),
                successorPlan.endpointFingerprint(), successorPlan.model() == null ? null
                : successorPlan.model().providerId(), successorPlan.model() == null ? null
                : successorPlan.model().modelId(), successorPlan.model() == null ? null
                : successorPlan.model().thinking(), successorPlan.profile().name(), permissionPolicyJson,
                successorPlan.permissionPolicyDigest(), successorPlan.createRequestSha256(),
                successorPlan.creationCredential(), "LOCAL_REQUEST_ATTESTED",
                null, null, null, 0, false, null, null, null, null, null, null, null,
                deterministic("acceptance-v7-legacy-prompt:" + id + ":1"), null,
                false, null, null, null, null, 0, false, null,
                null, null, null, now, now, 0);
        lifecycle.create(subject(created), created.state(), AcceptanceCandidateHandoffAudit.from(created),
                () -> mapper.insertAcceptanceCandidateLegacyHandoff(created), conflict());
        return created;
    }
    @Transactional
    AcceptanceCandidateLegacyHandoffRow recordOldDisconnected(String id, String code, String detail) {
        AcceptanceCandidateLegacyHandoffRow row = get(id);
        if (row.oldTerminationProof() != null) return row;
        LoopSpecCompilationRow owner = guard.currentOwner(row, row.oldExternalSessionId());
        if ("DISCONNECTED".equals(row.oldExternalState())
                && "DISCONNECTED".equals(owner.externalSessionState())) return row;
        LoopSpecCompilationRow disconnected = copyCompilation(owner, owner.externalSessionId(), "DISCONNECTED",
                code, safe(detail), owner.version());
        if (mapper.updateLoopSpecCompilation(disconnected) != 1) throw ownerConflict();
        AcceptanceCandidateLegacyHandoffRow update = copy(row, row.state(), owner.version() + 1,
                "DISCONNECTED", row.oldTerminationProof(), row.oldProofAt(), row.legacyExternalSessionId(),
                row.legacyRuntimeGenerationId(), row.legacyEndpointFingerprint(), row.legacyExternalState(),
                row.legacyTerminationProof(), row.legacyProofAt(), row.legacyPromptSha256(),
                row.modelCallConsumed(), row.modelCallConsumedAt(), "OLD_STOP", code, safe(detail));
        mutate(update);
        return get(id);
    }
    @Transactional
    AcceptanceCandidateLegacyHandoffRow confirmOldStopped(String id, String proof) {
        if (!CandidateSessionTerminationProof.persisted(proof)) {
            throw new ConflictException("ACCEPTANCE_CANDIDATE_STOP_UNCONFIRMED",
                    "旧验收候选会话缺少正向停止证明");
        }
        AcceptanceCandidateLegacyHandoffRow row = get(id);
        if (row.oldTerminationProof() != null) {
            if (!row.oldTerminationProof().equals(proof)) throw stale("ACCEPTANCE_LEGACY_HANDOFF_PROOF_STALE");
            return row;
        }
        LoopSpecCompilationRow owner = guard.currentOwner(row, row.oldExternalSessionId());
        LoopSpecCompilationRow proved = copyCompilation(owner, owner.externalSessionId(), proof,
                owner.lastErrorCode(), owner.lastErrorDetail(), owner.version());
        if (mapper.updateLoopSpecCompilation(proved) != 1) throw ownerConflict();
        String at = now();
        AcceptanceCandidateLegacyHandoffRow update = copy(row,
                AcceptanceCandidateHandoffState.OLD_STOPPED.name(), owner.version() + 1,
                proof, proof, at, row.legacyExternalSessionId(), row.legacyRuntimeGenerationId(),
                row.legacyEndpointFingerprint(), row.legacyExternalState(), row.legacyTerminationProof(),
                row.legacyProofAt(), row.legacyPromptSha256(), row.modelCallConsumed(),
                row.modelCallConsumedAt(), null, null, null);
        transition(row, update, LifecycleEvent.COMPLETE, "OLD_REMOTE_STOP_CONFIRMED");
        return get(id);
    }
    @Transactional
    Advance beginCreating(String id) {
        AcceptanceCandidateLegacyHandoffRow row = get(id);
        if (AcceptanceCandidateHandoffState.CREATING_LEGACY.name().equals(row.state())) {
            return new Advance(row, false);
        }
        if (!AcceptanceCandidateHandoffState.OLD_STOPPED.name().equals(row.state())) return new Advance(row, false);
        guard.currentOwner(row, row.oldExternalSessionId());
        AcceptanceCandidateLegacyHandoffRow update = copy(row,
                AcceptanceCandidateHandoffState.CREATING_LEGACY.name(), row.currentOwnerVersion(),
                row.oldExternalState(), row.oldTerminationProof(), row.oldProofAt(), null, null, null,
                null, null, null, null, false, null, null, null, null);
        transition(row, update, LifecycleEvent.DISPATCH, "LEGACY_CREATE_RESERVED");
        return new Advance(get(id), true);
    }
    @Transactional
    Claim claimCreate(String id, String claimant, Instant instant, Duration ttl) {
        AcceptanceCandidateLegacyHandoffRow row = get(id);
        boolean reconcilingStop = AcceptanceCandidateHandoffState.STOPPING_LEGACY.name().equals(row.state())
                && row.createDispatchAttempted();
        if ((!AcceptanceCandidateHandoffState.CREATING_LEGACY.name().equals(row.state()) && !reconcilingStop)
                || AcceptanceCandidateHandoffState.CREATING_LEGACY.name().equals(row.state())
                && row.legacyExternalSessionId() != null) return Claim.unavailable(row.createFence(), "STATE");
        if (active(row.createClaimExpiresAt(), instant)) {
            if (java.util.Objects.equals(claimant, row.createClaimOwner())) {
                return new Claim(true, row.createClaimToken(), row.createFence(), row.createClaimExpiresAt());
            }
            return Claim.unavailable(row.createFence(), "CLAIMED");
        }
        String token = UUID.randomUUID().toString();
        String expiresAt = instant.plus(ttl).toString();
        AcceptanceCandidateLegacyHandoffRow update = withClaims(row, claimant, token, expiresAt,
                row.createFence() + 1, row.promptClaimOwner(), row.promptClaimToken(),
                row.promptClaimExpiresAt(), row.promptFence());
        mutate(update);
        return new Claim(true, token, update.createFence(), expiresAt);
    }
    @Transactional
    AcceptanceCandidateLegacyHandoffRow fenceCreate(String id) {
        AcceptanceCandidateLegacyHandoffRow row = get(id);
        AcceptanceCandidateLegacyHandoffRow update = withClaims(row, null, null, null,
                row.createFence() + 1, row.promptClaimOwner(), row.promptClaimToken(),
                row.promptClaimExpiresAt(), row.promptFence());
        mutate(update);
        return get(id);
    }
    @Transactional
    DispatchCheckpoint markCreateDispatchStarted(String id, Claim claim) {
        AcceptanceCandidateLegacyHandoffRow row = get(id);
        requireCreateClaim(row, claim);
        if (row.createDispatchAttempted()) return new DispatchCheckpoint(row, false);
        AcceptanceCandidateLegacyHandoffRow update = withCreateDispatch(row, true, now());
        mutate(update);
        return new DispatchCheckpoint(get(id), true);
    }
    @Transactional(readOnly = true)
    boolean promptBudgetAvailable(String id, String revisionId) {
        AcceptanceCandidateLegacyHandoffRow row = get(id);
        if (!AcceptanceCandidateHandoffState.CREATING_LEGACY.name().equals(row.state())) {
            throw stale("ACCEPTANCE_LEGACY_HANDOFF_STATE_STALE");
        }
        guard.currentOwner(row, row.oldExternalSessionId());
        DesignRequirementRevisionRow revision = mapper.findDesignRequirementRevision(revisionId).orElseThrow();
        return revision.modelCallsUsed() < revision.maxModelCalls();
    }
    @Transactional
    List<AcceptanceCandidateHandoffCleanupRemoteRow> registerCleanupRemotes(
            String id, List<RemoteIdentity> matches, String code, String detail) {
        AcceptanceCandidateLegacyHandoffRow row = get(id);
        boolean stoppingUnknown = AcceptanceCandidateHandoffState.STOPPING_LEGACY.name().equals(row.state())
                && row.createDispatchAttempted() && row.legacyExternalSessionId() == null;
        if ((!AcceptanceCandidateHandoffState.CREATING_LEGACY.name().equals(row.state()) && !stoppingUnknown)
                || matches == null || matches.isEmpty()) {
            throw stale("ACCEPTANCE_LEGACY_HANDOFF_AMBIGUITY_STALE");
        }
        cleanupLedger.register(id, matches);
        AcceptanceCandidateLegacyHandoffRow failed = copy(row, row.state(), row.currentOwnerVersion(),
                row.oldExternalState(), row.oldTerminationProof(), row.oldProofAt(), row.legacyExternalSessionId(),
                row.legacyRuntimeGenerationId(), row.legacyEndpointFingerprint(), row.legacyExternalState(),
                row.legacyTerminationProof(), row.legacyProofAt(), row.legacyPromptSha256(),
                row.modelCallConsumed(), row.modelCallConsumedAt(), "LEGACY_CREATE",
                code, safe(detail));
        mutate(failed);
        return cleanupLedger.list(id);
    }
    List<AcceptanceCandidateHandoffCleanupRemoteRow> registerAmbiguity(
            String id, List<RemoteIdentity> matches) {
        if (matches == null || matches.size() < 2) {
            throw stale("ACCEPTANCE_LEGACY_HANDOFF_AMBIGUITY_STALE");
        }
        return registerCleanupRemotes(id, matches, "OPENCODE_SESSION_CREATION_AMBIGUOUS",
                "确定性创建凭据匹配到多个兼容候选 Session，必须逐个确认停止");
    }
    @Transactional
    AcceptanceCandidateLegacyHandoffRow recordLegacyCreated(String id,
            OpenCodeClient.SessionAttestation attestation, Claim claim) {
        AcceptanceCandidateLegacyHandoffRow row = get(id);
        OpenCodeClient.OpenCodeSession remote = attestation == null ? null : attestation.session();
        if (remote == null || remote.id() == null || remote.id().isBlank()) {
            throw stale("ACCEPTANCE_LEGACY_HANDOFF_REMOTE_STALE");
        }
        if (claim == null || !claim.acquired() || claim.fence() != row.createFence()
                || !java.util.Objects.equals(claim.token(), row.createClaimToken())) {
            throw stale("ACCEPTANCE_LEGACY_HANDOFF_CREATE_CLAIM_STALE");
        }
        plans.validate(row, attestation);
        if (row.legacyExternalSessionId() != null) {
            if (!row.legacyExternalSessionId().equals(remote.id())) {
                throw stale("ACCEPTANCE_LEGACY_HANDOFF_REMOTE_STALE");
            }
            guard.binding(remote.id(), row.legacyRuntimeGenerationId(), row.legacyEndpointFingerprint());
            return row;
        }
        boolean stopping = AcceptanceCandidateHandoffState.STOPPING_LEGACY.name().equals(row.state());
        if (!AcceptanceCandidateHandoffState.CREATING_LEGACY.name().equals(row.state()) && !stopping) {
            throw stale("ACCEPTANCE_LEGACY_HANDOFF_STATE_STALE");
        }
        var binding = guard.binding(remote.id());
        String revalidationFailure = null;
        try {
            guard.currentOwner(row, row.oldExternalSessionId());
        } catch (ConflictException staleOwner) {
            revalidationFailure = staleOwner.code();
        }
        boolean rejected = stopping || revalidationFailure != null;
        String target = rejected ? AcceptanceCandidateHandoffState.STOPPING_LEGACY.name()
                : AcceptanceCandidateHandoffState.LEGACY_CREATED.name();
        if (stopping && revalidationFailure == null) revalidationFailure = "DESIGNER_CANCELLED_DURING_CREATE";
        AcceptanceCandidateLegacyHandoffRow update = copy(row, target, row.currentOwnerVersion(),
                row.oldExternalState(), row.oldTerminationProof(), row.oldProofAt(), remote.id(),
                binding.runtimeGenerationId(), binding.endpointFingerprint(), "CREATED", null, null,
                null, false, null, rejected ? "OWNER_REVALIDATION" : null,
                revalidationFailure, rejected ? "Owner/source/binding changed after Legacy create" : null);
        update = withClaims(update, stopping ? row.createClaimOwner() : null,
                stopping ? row.createClaimToken() : null, stopping ? row.createClaimExpiresAt() : null,
                row.createFence(),
                row.promptClaimOwner(), row.promptClaimToken(), row.promptClaimExpiresAt(), row.promptFence());
        if (row.state().equals(update.state())) mutate(update);
        else transition(row, update, rejected ? LifecycleEvent.ABORT : LifecycleEvent.COMPLETE,
                    rejected ? "OWNER_REJECTED_AFTER_CREATE" : "LEGACY_REMOTE_PERSISTED");
        return get(id);
    }
    @Transactional
    DesignerAcceptanceCandidateOrchestrator.Start openLegacy(String id,
            DesignAcceptancePlanningRow planning, DesignerAcceptanceWorkflow.RoutingResult routing,
            OpenCodeClient.OpenCodeSession remote) {
        AcceptanceCandidateLegacyHandoffRow row = get(id);
        if (!AcceptanceCandidateHandoffState.LEGACY_CREATED.name().equals(row.state())) {
            throw stale("ACCEPTANCE_LEGACY_HANDOFF_STATE_STALE");
        }
        if (remote == null || !java.util.Objects.equals(row.legacyExternalSessionId(), remote.id())) {
            throw stale("ACCEPTANCE_LEGACY_HANDOFF_REMOTE_STALE");
        }
        guard.binding(remote.id(), row.legacyRuntimeGenerationId(), row.legacyEndpointFingerprint());
        LoopSpecCompilationRow owner = guard.currentOwner(row, row.oldExternalSessionId());
        LoopSpecCompilationRow attached = copyCompilation(owner, remote.id(), "CANDIDATE_LEGACY_RUNNING",
                null, null, owner.version());
        if (mapper.updateLoopSpecCompilation(attached) != 1) throw ownerConflict();
        LoopSpecCompilationRow stored = mapper.findLoopSpecCompilation(owner.id()).orElseThrow();
        DesignerAcceptanceCandidateOrchestrator.Start start = candidates.openLegacy(
                stored, planning, routing, remote, null);
        AcceptanceCandidateLegacyHandoffRow update = copy(row,
                AcceptanceCandidateHandoffState.LEGACY_OPENED.name(), stored.version(),
                row.oldExternalState(), row.oldTerminationProof(), row.oldProofAt(), remote.id(),
                row.legacyRuntimeGenerationId(), row.legacyEndpointFingerprint(), "CANDIDATE_LEGACY_RUNNING",
                null, null, row.legacyPromptSha256(), false, null, null, null, null);
        transition(row, update, LifecycleEvent.START, "LEGACY_RUN_OPENED");
        return start;
    }
    @Transactional
    PromptClaim claimPrompt(String id, DesignRequirementRevisionRow input, String promptSha256,
            String claimant, Instant instant, Duration ttl) {
        AcceptanceCandidateLegacyHandoffRow row = get(id);
        if (AcceptanceCandidateHandoffState.PROMPTING.name().equals(row.state()) && row.modelCallConsumed()) {
            if (!promptSha256.equals(row.legacyPromptSha256())) {
                throw stale("ACCEPTANCE_LEGACY_HANDOFF_PROMPT_STALE");
            }
            if (active(row.promptClaimExpiresAt(), instant)) {
                if (java.util.Objects.equals(claimant, row.promptClaimOwner())) {
                    return new PromptClaim(new Claim(true, row.promptClaimToken(), row.promptFence(),
                            row.promptClaimExpiresAt()), false);
                }
                return new PromptClaim(Claim.unavailable(row.promptFence(), "CLAIMED"), false);
            }
            String token = UUID.randomUUID().toString();
            String expiresAt = instant.plus(ttl).toString();
            AcceptanceCandidateLegacyHandoffRow reclaimed = withClaims(row,
                    row.createClaimOwner(), row.createClaimToken(), row.createClaimExpiresAt(), row.createFence(),
                    claimant, token, expiresAt, row.promptFence() + 1);
            mutate(reclaimed);
            return new PromptClaim(new Claim(true, token, reclaimed.promptFence(), expiresAt), false);
        }
        if (!AcceptanceCandidateHandoffState.LEGACY_OPENED.name().equals(row.state())) {
            return new PromptClaim(Claim.unavailable(row.promptFence(), "STATE"), false);
        }
        guard.currentOwner(row, row.legacyExternalSessionId());
        DesignRequirementRevisionRow revision = mapper.findDesignRequirementRevision(input.id()).orElseThrow();
        if (revision.modelCallsUsed() >= revision.maxModelCalls()) {
            return new PromptClaim(Claim.unavailable(row.promptFence(), "BUDGET_EXHAUSTED"), false);
        }
        DesignRequirementRevisionRow charged = new DesignRequirementRevisionRow(revision.id(),
                revision.designerSessionId(), revision.revision(), revision.sourceMessageId(),
                revision.requirementText(), revision.requirementSegmentsJson(), revision.sourceDraftVersion(),
                revision.state(), revision.modelCallsUsed() + 1, revision.maxModelCalls(), revision.createdAt(),
                now(), revision.version());
        if (mapper.updateDesignRequirementRevision(charged) != 1) throw stale("DESIGN_REQUIREMENT_VERSION_CONFLICT");
        String at = now();
        AcceptanceCandidateLegacyHandoffRow update = copy(row,
                AcceptanceCandidateHandoffState.PROMPTING.name(), row.currentOwnerVersion(),
                row.oldExternalState(), row.oldTerminationProof(), row.oldProofAt(), row.legacyExternalSessionId(),
                row.legacyRuntimeGenerationId(), row.legacyEndpointFingerprint(), row.legacyExternalState(), null,
                null, promptSha256, true, at, null, null, null);
        update = withPromptDispatch(update, true, at);
        String token = UUID.randomUUID().toString();
        String expiresAt = instant.plus(ttl).toString();
        update = withClaims(update, row.createClaimOwner(), row.createClaimToken(),
                row.createClaimExpiresAt(), row.createFence(), claimant, token, expiresAt,
                row.promptFence() + 1);
        transition(row, update, LifecycleEvent.DISPATCH, "LEGACY_PROMPT_RESERVED");
        return new PromptClaim(new Claim(true, token, update.promptFence(), expiresAt), true);
    }
    @Transactional
    AcceptanceCandidateLegacyHandoffRow markHandedOff(String id, Claim claim) {
        AcceptanceCandidateLegacyHandoffRow row = get(id);
        if (AcceptanceCandidateHandoffState.HANDED_OFF.name().equals(row.state())) return row;
        requirePromptClaim(row, claim);
        guard.currentOwner(row, row.legacyExternalSessionId());
        AcceptanceCandidateLegacyHandoffRow update = copy(row,
                AcceptanceCandidateHandoffState.HANDED_OFF.name(), row.currentOwnerVersion(),
                row.oldExternalState(), row.oldTerminationProof(), row.oldProofAt(), row.legacyExternalSessionId(),
                row.legacyRuntimeGenerationId(), row.legacyEndpointFingerprint(), "PROMPTED", null, null,
                row.legacyPromptSha256(), true, row.modelCallConsumedAt(), null, null, null);
        update = withClaims(update, null, null, null, row.createFence(),
                null, null, null, row.promptFence());
        transition(row, update, LifecycleEvent.COMPLETE, "LEGACY_PROMPT_CONFIRMED");
        return get(id);
    }
    @Transactional void requirePromptClaim(String id, Claim claim) {
        requirePromptClaim(get(id), claim);
    }
    private void requirePromptClaim(AcceptanceCandidateLegacyHandoffRow row, Claim claim) {
        DesignerSessionRow session = mapper.findDesignerSession(row.designerSessionId()).orElseThrow();
        if (!AcceptanceCandidateHandoffState.PROMPTING.name().equals(row.state())
                || "STOPPING".equals(session.state()) || "CANCELLED".equals(session.state())
                || claim == null || !claim.acquired() || claim.fence() != row.promptFence()
                || !java.util.Objects.equals(claim.token(), row.promptClaimToken())) {
            throw stale("ACCEPTANCE_LEGACY_HANDOFF_PROMPT_CLAIM_STALE");
        }
    }
    private void requireCreateClaim(AcceptanceCandidateLegacyHandoffRow row, Claim claim) {
        if (!AcceptanceCandidateHandoffState.CREATING_LEGACY.name().equals(row.state())
                || claim == null || !claim.acquired() || claim.fence() != row.createFence()
                || !java.util.Objects.equals(claim.token(), row.createClaimToken())) {
            throw stale("ACCEPTANCE_LEGACY_HANDOFF_CREATE_CLAIM_STALE");
        }
    }
    @Transactional void requireCancellationCreateClaim(String id, Claim claim) {
        AcceptanceCandidateLegacyHandoffRow row = get(id);
        if (!AcceptanceCandidateHandoffState.STOPPING_LEGACY.name().equals(row.state())
                || claim == null || !claim.acquired() || claim.fence() != row.createFence()
                || !java.util.Objects.equals(claim.token(), row.createClaimToken())) {
            throw stale("ACCEPTANCE_LEGACY_HANDOFF_CREATE_CLAIM_STALE");
        }
    }
    @Transactional void validateCancellationMatch(
            String id, OpenCodeClient.SessionAttestation attestation, Claim claim) {
        AcceptanceCandidateLegacyHandoffRow row = get(id);
        requireCancellationCreateClaim(id, claim);
        plans.validate(row, attestation);
    }
    @Transactional
    AcceptanceCandidateLegacyHandoffRow fencePrompt(String id, Claim claim) {
        AcceptanceCandidateLegacyHandoffRow row = get(id);
        if (claim == null || !claim.acquired() || claim.fence() != row.promptFence()
                || !java.util.Objects.equals(claim.token(), row.promptClaimToken())) {
            throw stale("ACCEPTANCE_LEGACY_HANDOFF_PROMPT_CLAIM_STALE");
        }
        AcceptanceCandidateLegacyHandoffRow update = withClaims(row,
                row.createClaimOwner(), row.createClaimToken(), row.createClaimExpiresAt(), row.createFence(),
                null, null, null, row.promptFence() + 1);
        mutate(update);
        return get(id);
    }
    @Transactional
    AcceptanceCandidateLegacyHandoffRow recordFailure(String id, String phase, String code, String detail) {
        AcceptanceCandidateLegacyHandoffRow row = get(id);
        long ownerVersion = row.currentOwnerVersion();
        if (row.legacyExternalSessionId() != null
                || ("LEGACY_CREATE".equals(phase) && row.createDispatchAttempted())) {
            LoopSpecCompilationRow owner = mapper.findLoopSpecCompilation(row.compilationId()).orElse(null);
            String expectedRemote = row.legacyExternalSessionId() == null
                    ? row.oldExternalSessionId() : row.legacyExternalSessionId();
            if (owner != null && owner.version() == row.currentOwnerVersion()
                    && java.util.Objects.equals(expectedRemote, owner.externalSessionId())) {
                String safeDetail = safe(detail);
                if (!("DISCONNECTED".equals(owner.externalSessionState())
                        && java.util.Objects.equals(code, owner.lastErrorCode())
                        && java.util.Objects.equals(safeDetail, owner.lastErrorDetail()))) {
                    LoopSpecCompilationRow disconnected = copyCompilation(owner, owner.externalSessionId(),
                            "DISCONNECTED", code, safeDetail, owner.version());
                    if (mapper.updateLoopSpecCompilation(disconnected) != 1) throw ownerConflict();
                    ownerVersion++;
                }
            }
        }
        AcceptanceCandidateLegacyHandoffRow update = copy(row, row.state(), ownerVersion,
                row.oldExternalState(), row.oldTerminationProof(), row.oldProofAt(), row.legacyExternalSessionId(),
                row.legacyRuntimeGenerationId(), row.legacyEndpointFingerprint(),
                row.legacyExternalSessionId() == null ? row.legacyExternalState() : "DISCONNECTED",
                row.legacyTerminationProof(), row.legacyProofAt(), row.legacyPromptSha256(),
                row.modelCallConsumed(), row.modelCallConsumedAt(), phase, code, safe(detail));
        mutate(update);
        return get(id);
    }
    @Transactional
    AcceptanceCandidateLegacyHandoffRow failWithoutSuccessor(String id, String code, String detail) {
        AcceptanceCandidateLegacyHandoffRow row = get(id);
        if (AcceptanceCandidateHandoffState.FAILED_STOPPED.name().equals(row.state())) return row;
        if (!AcceptanceCandidateHandoffState.CREATING_LEGACY.name().equals(row.state())
                || row.legacyExternalSessionId() != null
                || (row.oldExternalSessionId() != null && row.oldTerminationProof() == null)) {
            throw stale("ACCEPTANCE_LEGACY_HANDOFF_STATE_STALE");
        }
        guard.currentOwner(row, row.oldExternalSessionId());
        AcceptanceCandidateLegacyHandoffRow update = copy(row,
                AcceptanceCandidateHandoffState.FAILED_STOPPED.name(), row.currentOwnerVersion(),
                row.oldExternalState(), row.oldTerminationProof(), row.oldProofAt(), null, null, null,
                null, null, null, null, false, null, "LEGACY_CREATE", code, safe(detail));
        update = withClaims(update, null, null, null, row.createFence() + 1,
                null, null, null, row.promptFence() + 1);
        transition(row, update, LifecycleEvent.FAIL, "LEGACY_CREATION_FAILED_CLEANLY");
        return get(id);
    }
    @Transactional
    AcceptanceCandidateLegacyHandoffRow beginLegacyCleanup(String id, String phase, String code, String detail) {
        AcceptanceCandidateLegacyHandoffRow row = get(id);
        if (AcceptanceCandidateHandoffState.STOPPING_LEGACY.name().equals(row.state())) return row;
        AcceptanceCandidateLegacyHandoffRow update = copy(row,
                AcceptanceCandidateHandoffState.STOPPING_LEGACY.name(), row.currentOwnerVersion(),
                row.oldExternalState(), row.oldTerminationProof(), row.oldProofAt(), row.legacyExternalSessionId(),
                row.legacyRuntimeGenerationId(), row.legacyEndpointFingerprint(), "STOPPING", null, null,
                row.legacyPromptSha256(), row.modelCallConsumed(), row.modelCallConsumedAt(), phase, code, safe(detail));
        update = withClaims(update,
                row.createClaimOwner(), row.createClaimToken(), row.createClaimExpiresAt(), row.createFence(),
                null, null, null, row.promptFence() + 1);
        transition(row, update, LifecycleEvent.ABORT, "LEGACY_CLEANUP_REQUIRED");
        return get(id);
    }
    @Transactional AcceptanceCandidateLegacyHandoffRow completeLegacyCleanup(String id, String proof) {
        return termination.complete(id, proof);
    }
    @Transactional AcceptanceCandidateLegacyHandoffRow completeRecoveredUnknownCancellation(String id) {
        return termination.completeRecoveredUnknownCancellation(id);
    }
    @Transactional boolean prepareDesignerCancellation(String designerSessionId, Instant instant) {
        return termination.prepareDesignerCancellation(designerSessionId, instant);
    }
    @Transactional
    Map<String, String> cancelAfterDesignerRemotesStopped(
            String designerSessionId, Map<String, String> remoteProofs) {
        return termination.cancelAfterDesignerRemotesStopped(designerSessionId, remoteProofs);
    }
    private AcceptanceCandidateLegacyHandoffRow get(String id) {
        return mapper.findAcceptanceCandidateLegacyHandoff(id)
                .orElseThrow(() -> stale("ACCEPTANCE_LEGACY_HANDOFF_MISSING")); }
    private void mutate(AcceptanceCandidateLegacyHandoffRow update) {
        if (mapper.updateAcceptanceCandidateLegacyHandoff(update) != 1) {
            throw stale("ACCEPTANCE_LEGACY_HANDOFF_VERSION_CONFLICT");
        }
    }
    private void transition(AcceptanceCandidateLegacyHandoffRow from,
            AcceptanceCandidateLegacyHandoffRow to, LifecycleEvent event, String reason) {
        lifecycle.transition(subject(from), from.state(), to.state(), event, reason,
                AcceptanceCandidateHandoffAudit.from(to),
                () -> mapper.updateAcceptanceCandidateLegacyHandoff(to), conflict());
    }
    private LifecycleTransitionService.Subject subject(AcceptanceCandidateLegacyHandoffRow row) {
        return new LifecycleTransitionService.Subject(LifecycleMachineType.ACCEPTANCE_CANDIDATE_HANDOFF,
                row.id(), LifecycleScopeType.DESIGNER, row.designerSessionId());
    }
    private java.util.function.Supplier<RuntimeException> conflict() {
        return () -> stale("ACCEPTANCE_LEGACY_HANDOFF_VERSION_CONFLICT");
    }
    private static boolean active(String expiresAt, Instant instant) {
        return expiresAt != null && Instant.parse(expiresAt).isAfter(instant);
    }
    private static ConflictException ownerConflict() {
        return stale("LOOPSPEC_COMPILATION_VERSION_CONFLICT");
    }
    private static ConflictException stale(String code) {
        return new ConflictException(code, "验收候选兼容交接的 owner/source/session 已变化");
    }
    private static String deterministic(String value) {
        return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8)).toString();
    }
    private static String safe(String detail) {
        if (detail == null || detail.isBlank()) return "OpenCode handoff failed";
        String value = detail.replaceAll("[\\r\\n]+", " ").trim();
        return value.length() <= 500 ? value : value.substring(0, 500);
    }
    private static String now() { return Instant.now().toString(); }
    record Advance(AcceptanceCandidateLegacyHandoffRow row, boolean newlyEntered) { }
    record DispatchCheckpoint(AcceptanceCandidateLegacyHandoffRow row, boolean newlyStarted) { }
    record PromptClaim(Claim claim, boolean newlyStarted) { }
    record Claim(boolean acquired, String token, long fence, String expiresAt, String reason) {
        Claim(boolean acquired, String token, long fence, String expiresAt) {
            this(acquired, token, fence, expiresAt, null);
        }
        static Claim unavailable(long fence, String reason) {
            return new Claim(false, null, fence, null, reason);
        }
    }
    record RemoteIdentity(String externalSessionId, String runtimeGenerationId,
                          String endpointFingerprint, String directorySha256, String titleSha256) { }
}
