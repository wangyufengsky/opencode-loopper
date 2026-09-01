package io.opencode.loopper.service;

import io.opencode.loopper.domain.MachineCandidateKind;
import io.opencode.loopper.persistence.LoopperMachineCandidateMapper;
import io.opencode.loopper.persistence.RollingPackagePlanAcceptedResultRow;
import java.time.Instant;

/** Recompiles and persists the immutable canonical rolling plan in the ACCEPTED transaction. */
final class RollingPackagePlanAcceptedCandidateWriter implements AcceptedCandidateWriter {
    private final LoopperMachineCandidateMapper mapper;
    private final RollingPackagePlanCompilationInputLoader inputs;
    private final RollingPackagePlanCompilation compilation;

    RollingPackagePlanAcceptedCandidateWriter(
            LoopperMachineCandidateMapper mapper,
            RollingPackagePlanCompilationInputLoader inputs,
            RollingPackagePlanCompilation compilation) {
        this.mapper = mapper;
        this.inputs = inputs;
        this.compilation = compilation;
    }

    @Override
    public boolean supports(MachineCandidateKind kind) {
        return kind == MachineCandidateKind.ROLLING_PACKAGE_PLAN_V1;
    }

    @Override
    public void write(CandidatePolicy.Context context, String canonicalCandidateJson,
                      String canonicalResultSha256) {
        RollingPackagePlanCompilation.Result result = compilation.compileCandidate(
                inputs.load(context), canonicalCandidateJson);
        if (!result.accepted() || result.canonicalPlanJson() == null
                || result.canonicalImpactJson() == null
                || !canonicalCandidateJson.equals(result.canonicalCandidateJson())) {
            throw new ConflictException("ROLLING_PACKAGE_ACCEPTED_RESULT_INVALID",
                    "Accepted rolling package plan no longer compiles from frozen owner facts");
        }
        String now = Instant.now().toString();
        RollingPackagePlanAcceptedResultRow row = new RollingPackagePlanAcceptedResultRow(
                context.runId(), context.owner().id(), context.sourceRevision(), context.ownerVersion(),
                context.contractVersion(), result.canonicalCandidateJson(), result.canonicalPlanJson(),
                result.canonicalImpactJson(), canonicalResultSha256, null, now, now, 0);
        if (mapper.insertRollingPackagePlanAcceptedResult(row) != 1) {
            throw new ConflictException("ROLLING_PACKAGE_ACCEPTED_RESULT_CONFLICT",
                    "Accepted rolling package plan result could not be inserted");
        }
    }
}
