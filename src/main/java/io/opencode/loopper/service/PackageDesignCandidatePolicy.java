package io.opencode.loopper.service;

import io.opencode.loopper.domain.MachineCandidateKind;
import io.opencode.loopper.persistence.LoopperDesignerMapper;
import java.util.List;
import tools.jackson.databind.ObjectMapper;

/** DB-read-only PACKAGE_DESIGN_V1 candidate policy with mechanical-only retry/fallback. */
final class PackageDesignCandidatePolicy implements CandidatePolicy {
    static final String WORKFLOW_STEP = PackageDesignCandidateCodec.CONTRACT_VERSION;
    static final int MAX_ATTEMPTS = 3;
    private final PackageDesignCompilationInputLoader inputs;
    private final PackageDesignCompilation compilation;

    PackageDesignCandidatePolicy(LoopperDesignerMapper mapper, ObjectMapper json,
                                 PackageDesignCompilation compilation) {
        this(new PackageDesignCompilationInputLoader.MapperLoader(mapper, json), compilation);
    }

    PackageDesignCandidatePolicy(PackageDesignCompilationInputLoader inputs,
                                 PackageDesignCompilation compilation) {
        this.inputs = inputs;
        this.compilation = compilation;
    }

    @Override
    public boolean supports(MachineCandidateKind kind) {
        return kind == MachineCandidateKind.PACKAGE_DESIGN_V1;
    }

    @Override
    public Decision evaluate(Context context, String candidateJson) {
        if (context.candidateKind() != MachineCandidateKind.PACKAGE_DESIGN_V1
                || !WORKFLOW_STEP.equals(context.workflowStep())
                || !PackageDesignCandidateCodec.CONTRACT_VERSION.equals(context.contractVersion())
                || context.maxAttempts() != MAX_ATTEMPTS
                || context.owner().designWorkPackageId() == null) {
            return Decision.rejected(false, false, List.of(new MachineCandidateSubmission.Problem(
                    "PACKAGE_DESIGN_RUN_CONTRACT_INVALID", "/candidate",
                    "候选运行不属于 PACKAGE_DESIGN_V1 冻结合同", List.of())));
        }
        PackageDesignCompilation.Result result = compilation.compileCandidate(inputs.load(context), candidateJson);
        if (result.accepted()) return Decision.accepted(result.canonicalCandidateJson());
        boolean fallback = result.retryable() && !result.problems().isEmpty()
                && result.problems().stream().allMatch(PackageDesignCompilation.Problem::fallbackEligible);
        return Decision.rejected(result.retryable(), fallback,
                result.problems().stream().map(PackageDesignCompilation.Problem::submissionProblem).toList());
    }
}
