package io.opencode.loopper.service;

/** Deterministic primitives shared by compact Decomposer and Compiler contract compilation. */
public final class AiSemanticContractCompiler {
    private AiSemanticContractCompiler() { }

    public static String decompositionStatus(String outcome, int packageCount) {
        String normalized = outcome == null ? "" : outcome.trim().toUpperCase();
        if ("NEEDS_INPUT".equals(normalized) || "MULTI_TASK_REQUIRED".equals(normalized)) return normalized;
        if (!"READY".equals(normalized)) {
            throw new BadRequestException("DECOMPOSER_PLAN_OUTCOME_INVALID",
                    "Semantic outcome must be READY, NEEDS_INPUT, or MULTI_TASK_REQUIRED");
        }
        if (packageCount < 1 || packageCount > 6) {
            throw new BadRequestException("WORK_PACKAGE_COUNT_INVALID",
                    "READY decomposition must contain 1-6 semantic work packages");
        }
        return packageCount == 1 ? "DIRECT_DESIGN" : "DECOMPOSED";
    }

    public static String globalConstraintId(int zeroBasedIndex) { return "GC-" + (zeroBasedIndex + 1); }
    public static String workPackageId(int zeroBasedIndex) { return "WP-" + (zeroBasedIndex + 1); }
    public static String acceptanceId(String workPackageId, int oneBasedOrdinal) {
        return workPackageId + "-AC-" + oneBasedOrdinal;
    }

    public static String verificationMode(boolean machineEvidence, String judgeRubric, String judgeOnlyReason) {
        boolean judge = judgeRubric != null && !judgeRubric.isBlank();
        if (!machineEvidence && !judge) {
            throw new BadRequestException("COMPILER_PLAN_CRITERION_UNCOVERED",
                    "Criterion needs machine evidence or a judgeRubric");
        }
        if (!machineEvidence && (judgeOnlyReason == null || judgeOnlyReason.isBlank())) {
            throw new BadRequestException("COMPILER_PLAN_JUDGE_REASON_REQUIRED",
                    "Judge-only criterion needs judgeOnlyReason");
        }
        return machineEvidence ? (judge ? "BOTH" : "MACHINE") : "JUDGE";
    }
}
