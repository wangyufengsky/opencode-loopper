package io.opencode.loopper.service;

import io.opencode.loopper.domain.MachineCandidateKind;
import java.util.List;

/** DB-only PROJECT_CONVENTION_V1 policy; only closed mechanical errors may retry. */
final class ProjectConventionCandidatePolicy implements CandidatePolicy {
    static final String CONTRACT_VERSION = ProjectConventionCompilation.CONTRACT_VERSION;
    static final String WORKFLOW_STEP = CONTRACT_VERSION;
    static final int MAX_ATTEMPTS = 3;

    private final ProjectConventionCompilationInputLoader inputs;
    private final ProjectConventionCompilation compilation;

    ProjectConventionCandidatePolicy(
            ProjectConventionCompilationInputLoader inputs,
            ProjectConventionCompilation compilation) {
        this.inputs = inputs;
        this.compilation = compilation;
    }

    @Override
    public boolean supports(MachineCandidateKind kind) {
        return kind == MachineCandidateKind.PROJECT_CONVENTION_V1;
    }

    @Override
    public Decision evaluate(Context context, String candidateJson) {
        if (context.candidateKind() != MachineCandidateKind.PROJECT_CONVENTION_V1
                || context.scope().type() != MachineCandidateSubmission.CandidateScopeType.PROJECT
                || context.owner().type()
                    != MachineCandidateSubmission.CandidateOwnerType.PROJECT_CONVENTION_DRAFT
                || !WORKFLOW_STEP.equals(context.workflowStep())
                || !CONTRACT_VERSION.equals(context.contractVersion())
                || context.maxAttempts() != MAX_ATTEMPTS) {
            return Decision.rejected(false, false, List.of(new MachineCandidateSubmission.Problem(
                    "PROJECT_CONVENTION_RUN_CONTRACT_INVALID", "/candidate",
                    "候选运行不属于 PROJECT_CONVENTION_V1 冻结合同", List.of())));
        }
        ProjectConventionCompilation.Result result = compilation.compileCandidate(
                inputs.load(context), candidateJson);
        if (result.accepted()) return Decision.accepted(result.canonicalCandidateJson());
        return Decision.rejected(result.retryable(), false,
                result.problems().stream()
                        .map(ProjectConventionCompilation.Problem::submissionProblem)
                        .toList());
    }
}
