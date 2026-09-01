package io.opencode.loopper.service;

import io.opencode.loopper.domain.MachineCandidateKind;
import io.opencode.loopper.domain.MachineCandidateRunState;
import io.opencode.loopper.lifecycle.LifecycleTransitionService;
import io.opencode.loopper.persistence.CandidateSubmissionRunRow;
import io.opencode.loopper.persistence.LoopperMapper;
import io.opencode.loopper.persistence.LoopSpecCompilationRow;
import java.time.Instant;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Commits a remote termination proof only while its original run still owns the compilation. */
@Service
class AcceptanceCandidateProofService {
    private final LoopperMapper mapper;
    private final LifecycleTransitionService lifecycle;
    private final Optional<CandidateRuntimeBindingService> runtimeBindings;

    AcceptanceCandidateProofService(
            LoopperMapper mapper, LifecycleTransitionService lifecycle,
            Optional<CandidateRuntimeBindingService> runtimeBindings) {
        this.mapper = mapper;
        this.lifecycle = lifecycle;
        this.runtimeBindings = runtimeBindings;
    }

    @Transactional
    Optional<LoopSpecCompilationRow> persistIfOwned(
            MachineCandidateSubmission.RunSnapshot run, String proof) {
        try {
            return Optional.of(persist(run, proof));
        } catch (ConflictException staleOwner) {
            return Optional.empty();
        }
    }

    LoopSpecCompilationRow persist(MachineCandidateSubmission.RunSnapshot run, String proof) {
        if (run == null || run.candidateKind() != MachineCandidateKind.ACCEPTANCE_CLOSED_CHOICE_V7
                || run.state() != MachineCandidateRunState.ACCEPTED
                && run.state() != MachineCandidateRunState.WAITING_INPUT
                && run.state() != MachineCandidateRunState.CLOSED
                || !CandidateSessionTerminationProof.persisted(proof)) {
            throw new ConflictException("ACCEPTANCE_CANDIDATE_STOP_UNCONFIRMED",
                    "验收闭集候选缺少可绑定的远端终止证明");
        }
        CandidateSubmissionRunRow stored = mapper.findCandidateSubmissionRun(run.runId())
                .orElseThrow(() -> new ConflictException("ACCEPTANCE_CANDIDATE_RUN_MISSING",
                        "验收闭集候选运行已不存在"));
        requireSameRun(run, stored);
        var session = mapper.findDesignerSession(run.scope().id())
                .orElseThrow(() -> new ConflictException("CANDIDATE_OWNER_MISSING", "Designer owner no longer exists"));
        if ("STOPPING".equals(session.state()) || "CANCELLED".equals(session.state())) {
            throw new ConflictException("ACCEPTANCE_CANDIDATE_OWNER_STOPPING",
                    "Designer owner is stopping or cancelled");
        }
        LoopSpecCompilationRow owner = mapper.findLoopSpecCompilation(run.owner().id())
                .orElseThrow(() -> new ConflictException("CANDIDATE_OWNER_MISSING",
                        "LoopSpec compilation candidate owner no longer exists"));
        if (!run.scope().id().equals(owner.designerSessionId())
                || owner.designRevision() != run.sourceRevision()) {
            throw new ConflictException("CANDIDATE_OWNER_REVISION_STALE",
                    "LoopSpec compilation candidate owner or source revision has changed");
        }
        if (!run.externalSessionId().equals(owner.externalSessionId())) {
            throw new ConflictException("CANDIDATE_OWNER_SESSION_STALE",
                    "LoopSpec compilation candidate remote Session has changed");
        }
        if (!"RUNNING".equals(owner.state())) {
            throw new ConflictException("CANDIDATE_OWNER_STATE_INVALID",
                    "LoopSpec compilation candidate owner is no longer running");
        }
        runtimeBindings.ifPresent(binding -> binding.validate(run, run.submissionChannel()));
        if (same(owner.externalSessionState(), proof)) return owner;
        requirePreProofVersion(run, owner);

        String updatedAt = Instant.now().toString();
        LoopSpecCompilationRow update = copy(owner, proof, updatedAt, owner.version());
        lifecycle.mutateWithoutTransition(() -> mapper.updateLoopSpecCompilation(update),
                () -> new ConflictException("LOOPSPEC_COMPILATION_VERSION_CONFLICT",
                        "LoopSpec compilation was updated concurrently"));
        return copy(owner, proof, updatedAt, owner.version() + 1);
    }

    private void requireSameRun(MachineCandidateSubmission.RunSnapshot run, CandidateSubmissionRunRow stored) {
        if (!run.scope().id().equals(stored.designerSessionId())
                || !run.owner().type().name().equals(stored.ownerType())
                || !run.owner().id().equals(stored.ownerId())
                || !run.candidateKind().name().equals(stored.candidateKind())
                || !run.workflowStep().equals(stored.workflowStep())
                || run.sourceRevision() != stored.sourceRevision()
                || run.ownerVersion() != stored.ownerVersion()
                || !run.submissionChannel().name().equals(stored.submissionChannel())
                || !run.contractVersion().equals(stored.contractVersion())
                || !run.runtimeGenerationId().equals(stored.runtimeGenerationId())
                || !run.externalSessionId().equals(stored.externalSessionId())
                || !run.state().name().equals(stored.state())
                || run.version() != stored.version()) {
            throw new ConflictException("ACCEPTANCE_CANDIDATE_RUN_STALE",
                    "验收闭集候选运行在远端调用期间已经变化");
        }
    }

    private void requirePreProofVersion(
            MachineCandidateSubmission.RunSnapshot run, LoopSpecCompilationRow owner) {
        long expected = run.ownerVersion()
                + (run.state() == MachineCandidateRunState.ACCEPTED ? 1 : 0)
                + ("DISCONNECTED".equals(owner.externalSessionState()) ? 1 : 0);
        if (owner.version() != expected) {
            throw new ConflictException("CANDIDATE_OWNER_REVISION_STALE",
                    "LoopSpec compilation candidate owner revision has changed");
        }
    }

    private LoopSpecCompilationRow copy(
            LoopSpecCompilationRow row, String proof, String updatedAt, long version) {
        return new LoopSpecCompilationRow(row.id(), row.designerSessionId(), row.designRevision(), row.state(),
                row.externalSessionId(), proof, row.repairCount(), row.sourceDesignMessageId(),
                row.sourceDraftVersion(), row.lastErrorCode(), row.lastErrorDetail(), row.createdAt(), updatedAt,
                version, row.workPackageId(), row.transportRetryCount(), row.compiledPackageJson(),
                row.workflowStep(), row.planningJson(), row.planningRepairCount(), row.planningResponseMode(),
                row.planningResponseSchemaId(), row.planningFormatFallbackUsed(), row.finalResponseMode(),
                row.finalResponseSchemaId(), row.finalFormatFallbackUsed(), row.semanticPlanJson(),
                row.formatRepairCount(), row.semanticRepairCount(), row.serverCompiled(),
                row.compilationSource(), row.fallbackReason());
    }

    private boolean same(String left, String right) {
        return left == null ? right == null : left.equalsIgnoreCase(right);
    }
}
