package io.opencode.loopper.service;

import io.opencode.loopper.domain.DesignWorkPackageState;
import io.opencode.loopper.domain.MachineCandidateKind;
import io.opencode.loopper.domain.MachineCandidateRunState;
import io.opencode.loopper.persistence.DesignRequirementRevisionRow;
import io.opencode.loopper.persistence.LoopperMapper;
import io.opencode.loopper.persistence.OpenCodeSessionRuntimeBindingRow;
import io.opencode.loopper.persistence.TaskDecompositionRow;
import io.opencode.loopper.persistence.TaskPackagePlanRevisionRow;
import io.opencode.loopper.runtime.InternalMcpCredentialProvider;
import io.opencode.loopper.runtime.InternalMcpReadiness;
import io.opencode.loopper.runtime.InternalMcpRuntimeAccess;
import io.opencode.loopper.runtime.OpenCodeClient;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * Freezes the ownership and generation of every Session participating in the
 * candidate protocol, then revalidates that boundary before policy evaluation
 * and again inside the short accepted-candidate transaction.
 */
@Service
@ConditionalOnProperty(name = "loopper.internal-candidate.runtime-guard-enabled",
        havingValue = "true", matchIfMissing = true)
public final class CandidateRuntimeBindingService implements CandidateRunGuard {
    private final LoopperMapper mapper;
    private final InternalMcpRuntimeAccess runtimeAccess;

    public CandidateRuntimeBindingService(LoopperMapper mapper, InternalMcpRuntimeAccess runtimeAccess) {
        this.mapper = mapper;
        this.runtimeAccess = runtimeAccess;
    }

    public synchronized Binding bind(OpenCodeClient.OpenCodeSession session,
                                     MachineCandidateSubmission.SubmissionChannel channel) {
        if (session == null || blank(session.id()) || session.worktree() == null || channel == null) {
            throw new BadRequestException("CANDIDATE_RUNTIME_BINDING_INVALID",
                    "候选运行的 OpenCode Session 绑定参数不完整");
        }
        OpenCodeSessionRuntimeBindingRow existing = mapper.findOpenCodeSessionRuntimeBinding(session.id())
                .orElse(null);
        if (managed(session)) {
            if (existing == null || !"MANAGED".equals(existing.ownershipMode())
                    || !java.util.Objects.equals(existing.runtimeGenerationId(), session.generation())
                    || !java.util.Objects.equals(existing.internalMcpServer(), session.internalMcpServer())) {
                throw new ConflictException("CANDIDATE_RUNTIME_BINDING_STALE",
                        "OpenCode Session 缺少创建时的受管运行时代际绑定");
            }
            validateActiveManaged(existing);
            return binding(existing);
        }
        if (existing != null) {
            requireExistingExternal(existing, channel);
            return binding(existing);
        }
        OpenCodeSessionRuntimeBindingRow desired = externalBinding(session, channel);
        if (mapper.insertOpenCodeSessionRuntimeBinding(desired) != 1) {
            throw new ConflictException("CANDIDATE_RUNTIME_BINDING_CONFLICT",
                    "OpenCode Session 运行时代际绑定未能持久化");
        }
        return binding(desired);
    }

    /** Persists the first binding from the exact locally attested create request. */
    public synchronized Binding bindAttested(OpenCodeClient.SessionAttestation attestation,
                                             MachineCandidateSubmission.SubmissionChannel channel) {
        if (attestation == null
                || attestation.attestationKind() != OpenCodeClient.SessionAttestationKind.LOCAL_REQUEST_ATTESTED) {
            throw new BadRequestException("CANDIDATE_RUNTIME_BINDING_INVALID",
                    "候选运行缺少本地创建请求证明");
        }
        OpenCodeSessionRuntimeBindingRow existing = mapper
                .findOpenCodeSessionRuntimeBinding(attestation.remoteId()).orElse(null);
        String ownership = attestation.managed() ? "MANAGED" : "EXTERNAL";
        OpenCodeSessionRuntimeBindingRow desired = new OpenCodeSessionRuntimeBindingRow(
                attestation.remoteId(), attestation.runtimeGenerationId(), ownership,
                attestation.endpointFingerprint(), attestation.internalMcpServer(), Instant.now().toString());
        if (existing == null) {
            if (mapper.insertOpenCodeSessionRuntimeBinding(desired) != 1) {
                throw new ConflictException("CANDIDATE_RUNTIME_BINDING_CONFLICT",
                        "OpenCode Session 运行时代际绑定未能持久化");
            }
            existing = desired;
        }
        if (!existing.runtimeGenerationId().equals(desired.runtimeGenerationId())
                || !existing.ownershipMode().equals(desired.ownershipMode())
                || !existing.endpointFingerprint().equals(desired.endpointFingerprint())
                || !java.util.Objects.equals(existing.internalMcpServer(), desired.internalMcpServer())) {
            throw new ConflictException("CANDIDATE_RUNTIME_BINDING_STALE",
                    "OpenCode Session 本地创建证明与持久化绑定不一致");
        }
        if (channel != MachineCandidateSubmission.SubmissionChannel.IN_PROCESS_LEGACY) {
            throw new ConflictException("CANDIDATE_MANAGED_RUNTIME_REQUIRED",
                    "本地创建证明只用于进程内兼容候选通道");
        }
        if (attestation.managed()) validateActiveManaged(existing);
        return binding(existing);
    }

    /**
     * Binds an Acceptance internal-MCP remote only when its complete attestation is the
     * byte-for-byte semantic equivalent of the locally frozen create plan.  This is a
     * separate entry point so the legacy attestation seam above keeps its deliberately
     * narrow channel contract.
     */
    public synchronized Binding bindInternalAttested(
            OpenCodeClient.SessionAttestation attestation,
            OpenCodeClient.SessionCreationPlan frozenPlan) {
        if (attestation == null || frozenPlan == null
                || attestation.attestationKind()
                != OpenCodeClient.SessionAttestationKind.LOCAL_REQUEST_ATTESTED
                || !frozenPlan.equals(attestation.plan())
                || !attestation.managed()
                || blank(attestation.internalMcpServer())) {
            throw new ConflictException("CANDIDATE_INTERNAL_ATTESTATION_MISMATCH",
                    "内部 MCP 候选 Session 与冻结创建计划不一致");
        }
        OpenCodeSessionRuntimeBindingRow desired = new OpenCodeSessionRuntimeBindingRow(
                attestation.remoteId(), attestation.runtimeGenerationId(), "MANAGED",
                attestation.endpointFingerprint(), attestation.internalMcpServer(), Instant.now().toString());
        OpenCodeSessionRuntimeBindingRow existing = mapper
                .findOpenCodeSessionRuntimeBinding(attestation.remoteId()).orElse(null);
        if (existing == null) {
            if (mapper.insertOpenCodeSessionRuntimeBinding(desired) != 1) {
                throw new ConflictException("CANDIDATE_RUNTIME_BINDING_CONFLICT",
                        "OpenCode Session 运行时代际绑定未能持久化");
            }
            existing = desired;
        }
        if (!existing.runtimeGenerationId().equals(desired.runtimeGenerationId())
                || !"MANAGED".equals(existing.ownershipMode())
                || !existing.endpointFingerprint().equals(desired.endpointFingerprint())
                || !java.util.Objects.equals(existing.internalMcpServer(), desired.internalMcpServer())) {
            throw new ConflictException("CANDIDATE_RUNTIME_BINDING_STALE",
                    "内部 MCP 候选 Session 证明与持久化绑定不一致");
        }
        validateActiveManaged(existing);
        return binding(existing);
    }

    @Override
    public void validate(MachineCandidateSubmission.RunSnapshot run,
                         MachineCandidateSubmission.SubmissionChannel submissionChannel) {
        validate(run, submissionChannel, false);
    }

    void validateCorrectionStopRecovery(MachineCandidateSubmission.RunSnapshot run,
            MachineCandidateSubmission.SubmissionChannel submissionChannel) {
        if (run == null || run.candidateKind() != MachineCandidateKind.ACCEPTANCE_CLOSED_CHOICE_V7) {
            throw new IllegalArgumentException("Correction stop recovery requires an acceptance candidate run");
        }
        validate(run, submissionChannel, true);
    }

    private void validate(MachineCandidateSubmission.RunSnapshot run,
            MachineCandidateSubmission.SubmissionChannel submissionChannel, boolean correctionStopRecovery) {
        validateDesignerScopeWritable(run);
        OpenCodeSessionRuntimeBindingRow binding = mapper
                .findOpenCodeSessionRuntimeBinding(run.externalSessionId())
                .orElseThrow(() -> new ConflictException("CANDIDATE_RUNTIME_BINDING_STALE",
                        "OpenCode Session 运行时代际绑定不存在"));
        if (!binding.runtimeGenerationId().equals(run.runtimeGenerationId())) {
            throw new ConflictException("CANDIDATE_RUNTIME_BINDING_STALE",
                    "OpenCode Session 运行时代际绑定已经变化");
        }
        if (submissionChannel == MachineCandidateSubmission.SubmissionChannel.INTERNAL_MCP) {
            if (!"MANAGED".equals(binding.ownershipMode())) {
                throw new ConflictException("CANDIDATE_MANAGED_RUNTIME_REQUIRED",
                        "内部 MCP 仅接受受管 OpenCode Session");
            }
            if (!persistedAcceptanceTerminationProof(run)) validateActiveManaged(binding);
        } else if ("MANAGED".equals(binding.ownershipMode())) {
            // A fresh in-process fallback may still use a managed Session, but
            // it must never revive a Session from an earlier process generation.
            if (!persistedAcceptanceTerminationProof(run)) validateActiveManaged(binding);
        }
        validateOwnerAndSource(run, correctionStopRecovery);
    }

    private void validateDesignerScopeWritable(MachineCandidateSubmission.RunSnapshot run) {
        if (run.scope().type() != MachineCandidateSubmission.CandidateScopeType.DESIGNER_SESSION) return;
        mapper.findDesignerSession(run.scope().id()).ifPresent(session -> {
            if (!"RUNNING".equals(session.state())) {
                throw new ConflictException("CANDIDATE_SCOPE_NOT_WRITABLE",
                        "Designer session is not RUNNING and cannot create or advance a candidate writer");
            }
        });
    }

    private boolean persistedAcceptanceTerminationProof(MachineCandidateSubmission.RunSnapshot run) {
        if (run.candidateKind() != MachineCandidateKind.ACCEPTANCE_CLOSED_CHOICE_V7
                || run.state() != MachineCandidateRunState.ACCEPTED
                && run.state() != MachineCandidateRunState.WAITING_INPUT
                && run.state() != MachineCandidateRunState.FALLBACK_REQUIRED
                && run.state() != MachineCandidateRunState.CLOSED) return false;
        return mapper.findLoopSpecCompilation(run.owner().id())
                .filter(owner -> run.scope().id().equals(owner.designerSessionId())
                        && run.externalSessionId().equals(owner.externalSessionId())
                        && owner.designRevision() == run.sourceRevision()
                        && CandidateSessionTerminationProof.persisted(
                                owner.externalSessionState()))
                .isPresent();
    }

    private OpenCodeSessionRuntimeBindingRow externalBinding(
            OpenCodeClient.OpenCodeSession session,
            MachineCandidateSubmission.SubmissionChannel channel) {
        if (channel != MachineCandidateSubmission.SubmissionChannel.IN_PROCESS_LEGACY) {
            throw new ConflictException("CANDIDATE_MANAGED_RUNTIME_REQUIRED",
                    "内部 MCP 仅接受受管 OpenCode Session");
        }
        String fingerprint = sha256("EXTERNAL\n" + session.id() + "\n"
                + session.worktree().toAbsolutePath().normalize());
        return new OpenCodeSessionRuntimeBindingRow(session.id(), "external-" + fingerprint,
                "EXTERNAL", fingerprint, null, Instant.now().toString());
    }

    private void requireExistingExternal(OpenCodeSessionRuntimeBindingRow existing,
                                         MachineCandidateSubmission.SubmissionChannel channel) {
        if (channel != MachineCandidateSubmission.SubmissionChannel.IN_PROCESS_LEGACY) {
            throw new ConflictException("CANDIDATE_MANAGED_RUNTIME_REQUIRED",
                    "内部 MCP 仅接受受管 OpenCode Session");
        }
        if (!"EXTERNAL".equals(existing.ownershipMode())
                || blank(existing.runtimeGenerationId())
                || !existing.runtimeGenerationId().startsWith("external-")
                || blank(existing.endpointFingerprint())
                || existing.endpointFingerprint().length() != 64
                || !blank(existing.internalMcpServer())) {
            throw new ConflictException("CANDIDATE_RUNTIME_BINDING_CONFLICT",
                    "OpenCode Session 已绑定到不兼容的运行时代际");
        }
    }

    private void validateActiveManaged(OpenCodeSessionRuntimeBindingRow binding) {
        InternalMcpCredentialProvider.Credentials active = runtimeAccess.current()
                .orElseThrow(() -> new ConflictException("CANDIDATE_RUNTIME_GENERATION_STALE",
                        "受管 OpenCode 运行时代际已停止"));
        InternalMcpReadiness readiness = runtimeAccess.readiness();
        if (!active.generation().equals(binding.runtimeGenerationId())
                || !active.serverName().equals(binding.internalMcpServer())
                || !"CONNECTED".equals(readiness.status())
                || !active.generation().equals(readiness.generation())) {
            throw new ConflictException("CANDIDATE_RUNTIME_GENERATION_STALE",
                    "受管 OpenCode 运行时代际或内部 MCP 就绪证明已经变化");
        }
    }

    private void validateOwnerAndSource(
            MachineCandidateSubmission.RunSnapshot run, boolean correctionStopRecovery) {
        MachineCandidateProtocolPolicy.Contract protocol = MachineCandidateProtocolPolicy.contract(run.candidateKind());
        if (!protocol.integrated() || run.scope().type() != protocol.scopeType()
                || run.owner().type() != protocol.ownerType()) {
            throw new ConflictException("CANDIDATE_KIND_NOT_INTEGRATED",
                    "Candidate kind is not connected to an authoritative owner adapter");
        }
        validateIntegratedOwnerAndSource(run, correctionStopRecovery);
    }

    /** Package-local seam for owner guards whose protocol integration is enabled by their coordinator. */
    void validateIntegratedOwnerAndSource(MachineCandidateSubmission.RunSnapshot run) {
        MachineCandidateProtocolPolicy.Contract protocol = MachineCandidateProtocolPolicy.contract(run.candidateKind());
        if (run.scope().type() != protocol.scopeType() || run.owner().type() != protocol.ownerType()) {
            throw new ConflictException("CANDIDATE_KIND_NOT_INTEGRATED",
                    "Candidate kind is not connected to an authoritative owner adapter");
        }
        validateIntegratedOwnerAndSource(run, false);
    }

    private void validateIntegratedOwnerAndSource(
            MachineCandidateSubmission.RunSnapshot run, boolean correctionStopRecovery) {
        if (run.candidateKind() == MachineCandidateKind.DECOMPOSITION_PLAN_V2) {
            TaskDecompositionRow owner = mapper.findTaskDecomposition(run.owner().id())
                    .orElseThrow(() -> new ConflictException("CANDIDATE_OWNER_MISSING",
                            "Task decomposition candidate owner no longer exists"));
            if (!run.scope().id().equals(owner.designerSessionId())
                    || owner.version() != run.ownerVersion()) {
                throw new ConflictException("CANDIDATE_OWNER_REVISION_STALE",
                        "Task decomposition candidate owner revision has changed");
            }
            DesignRequirementRevisionRow revision = mapper
                    .findDesignRequirementRevision(owner.requirementRevisionId())
                    .filter(item -> run.scope().id().equals(item.designerSessionId())
                            && item.revision() == run.sourceRevision())
                    .orElseThrow(() -> new ConflictException("CANDIDATE_SOURCE_REVISION_STALE",
                            "Frozen requirement revision has changed"));
            return;
        }
        if (run.candidateKind() == MachineCandidateKind.PACKAGE_DESIGN_V1) {
            var owner = mapper.findDesignWorkPackage(run.owner().id())
                    .orElseThrow(() -> new ConflictException("CANDIDATE_OWNER_MISSING",
                            "Package design candidate owner no longer exists"));
            if (!run.scope().id().equals(owner.designerSessionId())
                    || owner.version() != run.ownerVersion()) {
                throw new ConflictException("CANDIDATE_OWNER_REVISION_STALE",
                        "Package design candidate owner revision has changed");
            }
            if (!DesignWorkPackageState.DESIGNING.name().equals(owner.state())
                    && !DesignWorkPackageState.QUESTIONING.name().equals(owner.state())) {
                throw new ConflictException("CANDIDATE_OWNER_STATE_INVALID",
                        "Package design candidate owner is no longer accepting candidates");
            }
            if (!run.externalSessionId().equals(owner.designerExternalSessionId())) {
                throw new ConflictException("CANDIDATE_OWNER_SESSION_STALE",
                        "Package design candidate owner remote Session has changed");
            }
            if ((long) owner.designRevision() + 1 != run.sourceRevision()) {
                throw new ConflictException("CANDIDATE_SOURCE_REVISION_STALE",
                        "Package design candidate source revision has changed");
            }
            return;
        }
        if (run.candidateKind() == MachineCandidateKind.ROLLING_PACKAGE_PLAN_V1) {
            validateRollingPackagePlanOwner(run);
            return;
        }
        if (run.candidateKind() == MachineCandidateKind.REVIEWER_REPORT_V1) {
            validateReviewerReportOwner(run);
            return;
        }
        if (run.candidateKind() == MachineCandidateKind.PROJECT_CONVENTION_V1) {
            validateProjectConventionOwner(run);
            return;
        }
        if (run.candidateKind() != MachineCandidateKind.ACCEPTANCE_CLOSED_CHOICE_V7) {
            throw new ConflictException("CANDIDATE_KIND_NOT_INTEGRATED",
                    "Candidate kind is not connected to an authoritative owner adapter");
        }
        var owner = mapper.findLoopSpecCompilation(run.owner().id())
                .orElseThrow(() -> new ConflictException("CANDIDATE_OWNER_MISSING",
                        "LoopSpec compilation candidate owner no longer exists"));
        boolean versionMatches = correctionStopRecovery
                ? AcceptanceCandidateOwnerCheckpoint.correctionStopRecoveryMatches(run, owner)
                : acceptanceOwnerVersionMatches(run, owner);
        if (!run.scope().id().equals(owner.designerSessionId()) || !versionMatches
                || owner.designRevision() != run.sourceRevision()) {
            throw new ConflictException("CANDIDATE_OWNER_REVISION_STALE",
                    "LoopSpec compilation candidate owner or source revision has changed");
        }
        if (!"RUNNING".equals(owner.state())) {
            throw new ConflictException("CANDIDATE_OWNER_STATE_INVALID",
                    "LoopSpec compilation candidate owner is no longer running");
        }
        if (!run.externalSessionId().equals(owner.externalSessionId())) {
            throw new ConflictException("CANDIDATE_OWNER_SESSION_STALE",
                    "LoopSpec compilation candidate remote Session has changed");
        }
    }

    private void validateReviewerReportOwner(MachineCandidateSubmission.RunSnapshot run) {
        var owner = mapper.findAnalysisReport(run.scope().id(), run.owner().id())
                .orElseThrow(() -> new ConflictException("CANDIDATE_OWNER_MISSING",
                        "Reviewer candidate owner no longer exists"));
        if (!run.scope().id().equals(owner.designerSessionId())
                || owner.version() != run.ownerVersion()
                || owner.sourceRequirementRevision() == null
                || owner.sourceRequirementRevision() != run.sourceRevision()
                || !run.contractVersion().equals(owner.reviewerContractVersion())) {
            throw new ConflictException("CANDIDATE_OWNER_REVISION_STALE",
                    "Reviewer candidate owner revision has changed");
        }
        if (!"RUNNING".equals(owner.state())) {
            throw new ConflictException("CANDIDATE_OWNER_STATE_INVALID",
                    "Reviewer candidate owner is no longer running");
        }
        if (!run.externalSessionId().equals(owner.externalSessionId())) {
            throw new ConflictException("CANDIDATE_OWNER_SESSION_STALE",
                    "Reviewer candidate owner remote Session has changed");
        }
        var snapshot = mapper.findReviewerReportSourceSnapshot(run.runId())
                .orElseThrow(() -> new ConflictException("CANDIDATE_SOURCE_REVISION_STALE",
                        "Frozen Reviewer source manifest is missing"));
        if (!run.owner().id().equals(snapshot.analysisReportId())
                || run.sourceRevision() != snapshot.sourceRevision()
                || run.ownerVersion() != snapshot.preparedOwnerVersion() + 1
                || !run.contractVersion().equals(snapshot.contractVersion())) {
            throw new ConflictException("CANDIDATE_SOURCE_REVISION_STALE",
                    "Frozen Reviewer source manifest has changed");
        }
    }

    private void validateProjectConventionOwner(MachineCandidateSubmission.RunSnapshot run) {
        var owner = mapper.findProjectConventionDraft(run.owner().id())
                .orElseThrow(() -> new ConflictException("CANDIDATE_OWNER_MISSING",
                        "Convention candidate owner no longer exists"));
        if (!run.scope().id().equals(owner.projectId())
                || owner.version() != run.ownerVersion()
                || !"INTERNAL_MCP".equals(owner.responseMode())
                || !"PROJECT_CONVENTION_V1".equals(run.contractVersion())) {
            throw new ConflictException("CANDIDATE_OWNER_REVISION_STALE",
                    "Convention candidate project scope or owner revision has changed");
        }
        if (owner.sourceRevision() == null || owner.sourceRevision() != run.sourceRevision()) {
            throw new ConflictException("CANDIDATE_SOURCE_REVISION_STALE",
                    "Convention candidate source revision has changed");
        }
        if (!"RUNNING".equals(owner.state())) {
            throw new ConflictException("CANDIDATE_OWNER_STATE_INVALID",
                    "Convention candidate owner is no longer running");
        }
        if (!run.externalSessionId().equals(owner.externalSessionId())) {
            throw new ConflictException("CANDIDATE_OWNER_SESSION_STALE",
                    "Convention candidate remote Session has changed");
        }
        var snapshot = mapper.findProjectConventionCandidateSourceSnapshot(run.runId())
                .orElseThrow(() -> new ConflictException("CANDIDATE_SOURCE_REVISION_STALE",
                        "Frozen Convention source and evidence snapshot is missing"));
        boolean preparedVersionMatches = snapshot.preparedOwnerVersion() != Long.MAX_VALUE
                && snapshot.preparedOwnerVersion() + 1 == run.ownerVersion();
        boolean evidenceAnchorMatches = !blank(snapshot.canonicalEvidenceJson())
                && !blank(snapshot.evidenceSha256())
                && snapshot.evidenceSha256().equals(sha256(snapshot.canonicalEvidenceJson()));
        boolean sourceAnchorMatches = snapshot.sourceContent() != null
                && !blank(snapshot.sourceContentSha256())
                && snapshot.sourceContentSha256().equals(sha256(snapshot.sourceContent()))
                && java.util.Objects.equals(owner.sourceSha256(), snapshot.sourceAgentsSha256())
                && java.util.Objects.equals(owner.sourceSha256(), snapshot.sourceContentSha256())
                && java.util.Objects.equals(owner.sourceContent(), snapshot.sourceContent())
                && owner.sourceExists() == snapshot.sourceExists();
        boolean stackAnchorMatches = java.util.Objects.equals(
                owner.projectStackProfileId(), snapshot.projectStackProfileId())
                && java.util.Objects.equals(owner.stackFingerprint(), snapshot.stackFingerprint());
        if (!run.runId().equals(snapshot.candidateRunId())
                || !run.scope().id().equals(snapshot.projectId())
                || !run.owner().id().equals(snapshot.projectConventionDraftId())
                || run.sourceRevision() != snapshot.sourceRevision()
                || !preparedVersionMatches
                || !run.contractVersion().equals(snapshot.contractVersion())
                || !sourceAnchorMatches || !stackAnchorMatches || !evidenceAnchorMatches) {
            throw new ConflictException("CANDIDATE_SOURCE_REVISION_STALE",
                    "Frozen Convention source or evidence snapshot has changed");
        }
    }

    private void validateRollingPackagePlanOwner(MachineCandidateSubmission.RunSnapshot run) {
        TaskPackagePlanRevisionRow owner = mapper.findTaskPackagePlanRevision(run.owner().id())
                .orElseThrow(() -> new ConflictException("CANDIDATE_OWNER_MISSING",
                        "Rolling package plan candidate owner no longer exists"));
        if (!"GENERATING".equals(owner.state())) {
            throw new ConflictException("CANDIDATE_OWNER_STATE_INVALID",
                    "Rolling package plan candidate owner is no longer generating");
        }
        if (!run.scope().id().equals(owner.taskId())) {
            throw new ConflictException("CANDIDATE_OWNER_REVISION_STALE",
                    "Rolling package plan candidate task scope has changed");
        }
        if (owner.revision() != run.sourceRevision()) {
            throw new ConflictException("CANDIDATE_SOURCE_REVISION_STALE",
                    "Rolling package plan candidate source revision has changed");
        }
        if (!run.externalSessionId().equals(owner.externalSessionId())) {
            throw new ConflictException("CANDIDATE_OWNER_SESSION_STALE",
                    "Rolling package plan candidate remote Session has changed");
        }
        boolean exactOwnerVersion = owner.version() == run.ownerVersion();
        boolean runningDispatchStep = run.ownerVersion() != Long.MAX_VALUE
                && "RUNNING".equals(owner.externalSessionState())
                && owner.version() == run.ownerVersion() + 1;
        if (!exactOwnerVersion && !runningDispatchStep) {
            throw new ConflictException("CANDIDATE_OWNER_REVISION_STALE",
                    "Rolling package plan candidate owner revision has changed");
        }
    }

    private boolean acceptanceOwnerVersionMatches(
            MachineCandidateSubmission.RunSnapshot run,
            io.opencode.loopper.persistence.LoopSpecCompilationRow owner) {
        if (AcceptanceCandidateOwnerCheckpoint.settledCorrectionStopMarker(owner)) {
            long proofVersion = AcceptanceCandidateOwnerCheckpoint.correctionProofVersion(run);
            if (owner.version() == proofVersion && run.state().terminal()) return true;
            return run.state() == MachineCandidateRunState.ACCEPTED
                    && acceptedServerCompilationCheckpoint(owner, proofVersion);
        }
        if (run.state() == MachineCandidateRunState.ACCEPTED) {
            long expected = run.ownerVersion() + 1;
            if ("DISCONNECTED".equals(owner.externalSessionState())) expected++;
            if (CandidateSessionTerminationProof.persisted(owner.externalSessionState())) {
                expected++;
                if (!blank(owner.lastErrorCode())) expected++;
            }
            if (owner.version() == expected) return true;
            return acceptedServerCompilationCheckpoint(owner, expected);
        }
        if (run.state() == MachineCandidateRunState.WAITING_INPUT
                || run.state() == MachineCandidateRunState.FALLBACK_REQUIRED) {
            long expected = run.ownerVersion();
            if ("DISCONNECTED".equals(owner.externalSessionState())) expected++;
            if (CandidateSessionTerminationProof.persisted(owner.externalSessionState())) {
                expected++;
                if (!blank(owner.lastErrorCode())) expected++;
            }
            return owner.version() == expected;
        }
        if (run.state() == MachineCandidateRunState.CLOSED) {
            long expected = run.ownerVersion();
            if ("DISCONNECTED".equals(owner.externalSessionState())) expected++;
            if (CandidateSessionTerminationProof.persisted(owner.externalSessionState())) {
                expected++;
                if (!blank(owner.lastErrorCode())) expected++;
            }
            return owner.version() == expected;
        }
        return AcceptanceCandidateOwnerCheckpoint.openVersionMatches(run.ownerVersion(), owner);
    }

    private boolean acceptedServerCompilationCheckpoint(
            io.opencode.loopper.persistence.LoopSpecCompilationRow owner, long proofVersion) {
        if (!CandidateSessionTerminationProof.persisted(owner.externalSessionState())
                || !"RUNNING".equals(owner.state())
                || !"SERVER_COMPILING".equals(owner.workflowStep())
                || blank(owner.planningJson())
                || blank(owner.semanticPlanJson())) return false;
        if (!owner.serverCompiled()) {
            return owner.version() == proofVersion + 1;
        }
        return owner.version() == proofVersion + 2;
    }

    private boolean managed(OpenCodeClient.OpenCodeSession session) {
        return !blank(session.generation()) || !blank(session.internalMcpServer());
    }

    private Binding binding(OpenCodeSessionRuntimeBindingRow row) {
        return new Binding(row.externalSessionId(), row.runtimeGenerationId(), row.ownershipMode());
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }

    public record Binding(String externalSessionId, String runtimeGenerationId, String ownershipMode) { }
}
