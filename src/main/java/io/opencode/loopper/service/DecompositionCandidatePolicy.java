package io.opencode.loopper.service;

import io.opencode.loopper.domain.MachineCandidateKind;
import io.opencode.loopper.persistence.DesignRequirementRevisionRow;
import io.opencode.loopper.persistence.LoopperDesignerMapper;
import io.opencode.loopper.persistence.TaskDecompositionRow;

/** Database-only adapter from the generic candidate loop to the pure Decomposer compiler. */
final class DecompositionCandidatePolicy implements CandidatePolicy {
    private final LoopperDesignerMapper mapper;
    private final DesignerDecompositionCandidateCompiler compiler;

    DecompositionCandidatePolicy(LoopperDesignerMapper mapper, DesignerDecompositionCandidateCompiler compiler) {
        this.mapper = mapper;
        this.compiler = compiler;
    }

    @Override
    public boolean supports(MachineCandidateKind kind) {
        return kind == MachineCandidateKind.DECOMPOSITION_PLAN_V2;
    }

    @Override
    public Decision evaluate(Context context, String candidateJson) {
        TaskDecompositionRow owner = mapper.findTaskDecomposition(context.owner().id())
                .orElseThrow(() -> new ConflictException(
                        "CANDIDATE_OWNER_MISSING", "Task decomposition candidate owner no longer exists"));
        if (!context.scope().id().equals(owner.designerSessionId())
                || owner.version() != context.ownerVersion()) {
            throw new ConflictException("CANDIDATE_OWNER_REVISION_STALE",
                    "Task decomposition candidate owner revision has changed");
        }
        DesignRequirementRevisionRow revision = mapper.findDesignRequirementRevision(owner.requirementRevisionId())
                .filter(item -> context.scope().id().equals(item.designerSessionId())
                        && item.revision() == context.sourceRevision())
                .orElseThrow(() -> new ConflictException(
                        "CANDIDATE_REQUIREMENT_MISSING", "Frozen requirement revision no longer exists"));
        DesignerDecompositionCandidateCompiler.Compilation result = compiler.compile(candidateJson, revision);
        if (!result.accepted()) return Decision.rejected(true, result.problems());
        if (result.boundaryProblem() != null) {
            return Decision.rejected(false, java.util.List.of(result.boundaryProblem()));
        }
        return Decision.accepted(result.canonicalJson());
    }
}
