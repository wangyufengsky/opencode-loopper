package io.opencode.loopper.service;

import io.opencode.loopper.domain.MachineCandidateKind;
import java.util.List;

/** DB-read-only REVIEWER_REPORT_V1 policy; only closed mechanical errors may retry. */
final class ReviewerReportCandidatePolicy implements CandidatePolicy {
    static final String CONTRACT_VERSION = "REVIEWER_REPORT_V1";
    static final String WORKFLOW_STEP = CONTRACT_VERSION;
    static final int MAX_ATTEMPTS = 3;

    private final ReviewerReportCandidateCodec codec;
    private final ReviewerReportCompilationInputLoader inputs;
    private final ReviewerReportCompilation compilation;

    ReviewerReportCandidatePolicy(
            ReviewerReportCandidateCodec codec,
            ReviewerReportCompilationInputLoader inputs,
            ReviewerReportCompilation compilation) {
        this.codec = codec;
        this.inputs = inputs;
        this.compilation = compilation;
    }

    @Override
    public boolean supports(MachineCandidateKind kind) {
        return kind == MachineCandidateKind.REVIEWER_REPORT_V1;
    }

    @Override
    public Decision evaluate(Context context, String candidateJson) {
        if (context.candidateKind() != MachineCandidateKind.REVIEWER_REPORT_V1
                || !WORKFLOW_STEP.equals(context.workflowStep())
                || !CONTRACT_VERSION.equals(context.contractVersion())
                || context.maxAttempts() != MAX_ATTEMPTS
                || context.scope().type()
                    != MachineCandidateSubmission.CandidateScopeType.DESIGNER_SESSION
                || context.owner().type()
                    != MachineCandidateSubmission.CandidateOwnerType.ANALYSIS_REPORT) {
            return Decision.rejected(false, false, List.of(new MachineCandidateSubmission.Problem(
                    "REVIEWER_RUN_CONTRACT_INVALID", "/candidate",
                    "候选运行不属于 REVIEWER_REPORT_V1 冻结合同", List.of())));
        }
        ReviewerReportCandidateCodec.Decoded decoded = codec.decodeCandidate(candidateJson);
        if (!decoded.valid()) {
            return Decision.rejected(!decoded.security(), false, List.of(decoded.problem()));
        }
        ReviewerReportCompilation.Result result = compilation.compile(
                inputs.load(context, decoded.candidate()));
        if (result.accepted()) return Decision.accepted(result.canonicalCandidateJson());
        return Decision.rejected(result.retryable(), false, result.problems().stream()
                .map(problem -> new MachineCandidateSubmission.Problem(
                        problem.code(), problem.pointer(), problem.staticDetail(), problem.allowedValues()))
                .toList());
    }
}
