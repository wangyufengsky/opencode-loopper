package io.opencode.loopper.service;

import io.opencode.loopper.domain.MachineCandidateKind;
import java.util.List;

/** DB-read-only ROLLING_PACKAGE_PLAN_V1 policy; only closed mechanical errors can retry. */
final class RollingPackagePlanCandidatePolicy implements CandidatePolicy {
    static final String CONTRACT_VERSION = "ROLLING_PACKAGE_PLAN_V1";
    static final String WORKFLOW_STEP = CONTRACT_VERSION;
    static final int MAX_ATTEMPTS = 3;

    private final RollingPackagePlanCompilationInputLoader inputs;
    private final RollingPackagePlanCompilation compilation;

    RollingPackagePlanCandidatePolicy(RollingPackagePlanCompilationInputLoader inputs,
                                      RollingPackagePlanCompilation compilation) {
        this.inputs = inputs;
        this.compilation = compilation;
    }

    @Override
    public boolean supports(MachineCandidateKind kind) {
        return kind == MachineCandidateKind.ROLLING_PACKAGE_PLAN_V1;
    }

    @Override
    public Decision evaluate(Context context, String candidateJson) {
        if (context.candidateKind() != MachineCandidateKind.ROLLING_PACKAGE_PLAN_V1
                || !WORKFLOW_STEP.equals(context.workflowStep())
                || !CONTRACT_VERSION.equals(context.contractVersion())
                || context.maxAttempts() != MAX_ATTEMPTS
                || context.owner().type()
                    != MachineCandidateSubmission.CandidateOwnerType.TASK_PACKAGE_PLAN_REVISION) {
            return Decision.rejected(false, false, List.of(new MachineCandidateSubmission.Problem(
                    "ROLLING_PACKAGE_RUN_CONTRACT_INVALID", "/candidate",
                    "候选运行不属于 ROLLING_PACKAGE_PLAN_V1 冻结合同", List.of())));
        }
        RollingPackagePlanCompilation.Result result = compilation.compileCandidate(inputs.load(context), candidateJson);
        if (result.accepted()) return Decision.accepted(result.canonicalCandidateJson());
        return Decision.rejected(result.retryable(), false, result.problems().stream()
                .map(problem -> new MachineCandidateSubmission.Problem(
                        problem.code(), problem.pointer(), problem.staticDetail(), problem.allowedValues()))
                .toList());
    }
}
