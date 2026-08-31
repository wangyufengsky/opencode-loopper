package io.opencode.loopper.service;

import io.opencode.loopper.domain.MachineCandidateKind;
import io.opencode.loopper.domain.StructuredModelStep;
import io.opencode.loopper.persistence.LoopperDesignerMapper;
import io.opencode.loopper.persistence.TaskDecompositionRow;
import java.time.Instant;

/** Projects an accepted canonical Decomposer plan into the existing authoritative owner row. */
final class DecompositionAcceptedCandidateWriter implements AcceptedCandidateWriter {
    private final LoopperDesignerMapper mapper;

    DecompositionAcceptedCandidateWriter(LoopperDesignerMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public boolean supports(MachineCandidateKind kind) {
        return kind == MachineCandidateKind.DECOMPOSITION_PLAN_V2;
    }

    @Override
    public void write(CandidatePolicy.Context context, String canonicalCandidateJson,
                      String canonicalResultSha256) {
        TaskDecompositionRow row = mapper.findTaskDecomposition(context.owner().taskDecompositionId())
                .orElseThrow(() -> new ConflictException(
                        "CANDIDATE_OWNER_MISSING", "Task decomposition candidate owner no longer exists"));
        if (!context.designerSessionId().equals(row.designerSessionId())
                || row.version() != context.ownerVersion()) {
            throw new ConflictException("CANDIDATE_OWNER_REVISION_STALE",
                    "Task decomposition candidate owner revision has changed");
        }
        TaskDecompositionRow updated = new TaskDecompositionRow(row.id(), row.designerSessionId(),
                row.requirementRevisionId(), row.state(), row.resultType(), row.normalizedGoal(),
                row.globalConstraintsJson(), row.planJson(), row.externalSessionId(), row.externalSessionState(),
                row.repairCount(), row.transportRetryCount(), row.sourceDraftVersion(), row.lastErrorCode(),
                row.lastErrorDetail(), row.createdAt(), Instant.now().toString(), row.version(),
                StructuredModelStep.SERVER_COMPILING.name(), canonicalCandidateJson, row.planningRepairCount(),
                row.planningResponseMode(), row.planningResponseSchemaId(), row.planningFormatFallbackUsed(),
                row.finalResponseMode(), row.finalResponseSchemaId(), row.finalFormatFallbackUsed(),
                canonicalCandidateJson, row.formatRepairCount(), row.semanticRepairCount(), true);
        if (mapper.updateTaskDecomposition(updated) != 1) {
            throw new ConflictException("TASK_DECOMPOSITION_VERSION_CONFLICT",
                    "Task decomposition was updated concurrently");
        }
    }
}
