package io.opencode.loopper.service;

import io.opencode.loopper.domain.MachineCandidateKind;
import java.util.List;

/** DB-only JUDGE_DECISION_V1 policy; only closed mechanical errors may retry in the same Session. */
final class JudgeDecisionCandidatePolicy implements CandidatePolicy {
    static final String CONTRACT_VERSION = JudgeDecisionCompilation.CONTRACT_VERSION;
    static final String WORKFLOW_STEP = CONTRACT_VERSION;
    static final int MAX_ATTEMPTS = 2;

    private final JudgeDecisionCompilationInputLoader inputs;
    private final JudgeDecisionCompilation compilation;

    JudgeDecisionCandidatePolicy(
            JudgeDecisionCompilationInputLoader inputs, JudgeDecisionCompilation compilation) {
        this.inputs = inputs;
        this.compilation = compilation;
    }

    @Override
    public boolean supports(MachineCandidateKind kind) {
        return kind == MachineCandidateKind.JUDGE_DECISION_V1;
    }

    @Override
    public Decision evaluate(Context context, String candidateJson) {
        if (context.candidateKind() != MachineCandidateKind.JUDGE_DECISION_V1
                || context.scope().type() != MachineCandidateSubmission.CandidateScopeType.TASK
                || context.owner().type() != MachineCandidateSubmission.CandidateOwnerType.JUDGE_RUN
                || !WORKFLOW_STEP.equals(context.workflowStep())
                || !CONTRACT_VERSION.equals(context.contractVersion())
                || context.maxAttempts() != MAX_ATTEMPTS) {
            return Decision.rejected(false, false, List.of(new MachineCandidateSubmission.Problem(
                    "JUDGE_RUN_CONTRACT_INVALID", "/candidate",
                    "候选运行不属于 JUDGE_DECISION_V1 冻结合同", List.of())));
        }
        JudgeDecisionCompilation.Result result = compilation.compileCandidate(inputs.load(context), candidateJson);
        if (result.accepted()) return Decision.accepted(result.canonicalCandidateJson());
        return Decision.rejected(result.retryable(), false,
                result.problems().stream().map(JudgeDecisionCompilation.Problem::submissionProblem).toList());
    }
}
