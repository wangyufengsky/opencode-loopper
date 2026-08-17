package io.opencode.loopper.service;

import java.util.Locale;

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

    /**
     * Closed, conservative recognition of engineering metadata that must not become a business acceptance
     * criterion merely because a weak model listed it under criteria. The frozen Designer text remains available
     * to Implementation; only the redundant AC/evidence association is removed by the server compiler.
     */
    public static boolean isEngineeringMetaCriterion(String description) {
        if (description == null || description.isBlank()) return false;
        String text = description.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
        boolean buildOrTestResult = containsAny(text,
                "mvn test", "mvn package", "gradle test", "gradle build", "npm test",
                "full test", "full-suite", "tests pass", "build succeeds", "build success",
                "全量测试通过", "聚焦测试通过", "构建成功", "打包成功", "退出码 0", "退出码0");
        boolean codeStyle = containsAny(text,
                "无 var", "不使用 var", "钻石语法", "中文注释", "java 8 语法", "java8 语法",
                "code style", "coding style", "source style", "代码风格", "编码规范");
        boolean sourceShape = containsAny(text,
                "源码检查", "源代码检查", "@functionalinterface", "final 类", "final class",
                "私有构造", "private constructor", "静态字符串常量", "static final string",
                "接口声明形态", "注解形态", "装配形态");
        boolean deliveryHygiene = containsAny(text,
                "无越界路径", "不越界", "无意外删除", "无未提交生成物", "无残留进程",
                "clean worktree", "no out-of-scope paths", "no unexpected deletions");
        return buildOrTestResult || codeStyle || sourceShape || deliveryHygiene;
    }

    private static boolean containsAny(String text, String... candidates) {
        for (String candidate : candidates) if (text.contains(candidate)) return true;
        return false;
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
