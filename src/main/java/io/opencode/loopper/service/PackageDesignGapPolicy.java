package io.opencode.loopper.service;

import java.util.List;

/** Versioned reinterpretation of candidate-owned gap claims; never weakens deterministic preflight failures. */
final class PackageDesignGapPolicy {
    static final String WORKFLOW_STEP = "PACKAGE_DESIGN_V1_EVIDENCE_V1";
    private PackageDesignGapPolicy() { }

    static String workflowStep(String existing, boolean enabled) {
        return existing != null ? existing : enabled ? WORKFLOW_STEP : PackageDesignCandidateCodec.CONTRACT_VERSION;
    }

    static PackageDesignCompilation.Result apply(PackageDesignCompilation.Result result) {
        if (result.accepted()) return result;
        var problems = result.problems().stream().map(PackageDesignGapPolicy::assess).toList();
        boolean retryable = !problems.isEmpty() && problems.stream().allMatch(problem ->
                problem.problemClass() == PackageDesignCompilation.ProblemClass.CORRECTABLE
                        || problem.problemClass() == PackageDesignCompilation.ProblemClass.MECHANICAL);
        return new PackageDesignCompilation.Result(retryable ? PackageDesignCompilation.Outcome.REJECTED : result.outcome(),
                result.canonicalCandidateJson(), result.canonicalMarkdown(), result.compiledPlan(),
                result.compiledResultJson(), problems);
    }

    private static PackageDesignCompilation.Problem assess(PackageDesignCompilation.Problem problem) {
        // Only a recognized model claim at /gapCodes/N is reclassified. Scope/security/compiler failures retain authority.
        if (problem.problemClass() == PackageDesignCompilation.ProblemClass.SECURITY
                || problem.pointer() == null || !problem.pointer().matches("/gapCodes/[0-9]+")
                || java.util.Arrays.stream(DesignerSemanticContracts.DesignGapCode.values())
                    .noneMatch(code -> code.name().equals(problem.code()))) return problem;
        var assessment = new PackageDesignGapAssessment().assess(problem.code(), List.of());
        return new PackageDesignCompilation.Problem("PACKAGE_GAP_" + assessment.category().name(), problem.pointer(),
                assessment.detail(), List.of(), PackageDesignCompilation.ProblemClass.CORRECTABLE, false,
                "有冻结需求来源支持的缺口依据；未知事实须保持尚未确认", "模型声明 " + problem.code(),
                "处理动作=" + assessment.action().name() + "；定位原文及可观察分歧；保留已知需求。"
                        + "若问题属于候选表达，修正后提交完整 READY；不能编造缺失的业务决定或仓库能力");
    }
}
