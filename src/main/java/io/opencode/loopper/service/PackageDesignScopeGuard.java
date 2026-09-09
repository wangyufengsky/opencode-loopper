package io.opencode.loopper.service;

import static io.opencode.loopper.service.DesignerAcceptancePlanning.*;
import static io.opencode.loopper.service.PackageDesignCompilation.*;

import io.opencode.loopper.verification.VerifierPathPolicy;
import java.util.ArrayList;
import java.util.List;

/** Proves the final executable scope against frozen input, never against candidate declarations. */
final class PackageDesignScopeGuard {
    private PackageDesignScopeGuard() { }

    static List<Problem> validate(Input input, DesignerSemanticContracts.PackageCompilationPlanEnvelope plan) {
        var policy = new DesignerAcceptancePathPolicy();
        var allowed = policy.paths(input.scopeIn());
        var empty = new Catalog(CONTRACT_VERSION_V7, input.workPackage().packageId(),
                input.workPackage().designRevision(), "", true, List.of(), List.of(), List.of());
        if (allowed.isEmpty() && input.directSoftwareMode()) {
            var source = new DesignerMutationObligationExtractor().extractUsingFrozenScope(empty,
                    input.requirementText(), List.of(), input.scopeOut(), List.of(), true);
            allowed = source.mutationObligations().stream()
                    .filter(item -> item.operation() == MutationOperation.WRITE)
                    .filter(item -> item.sourceExcerpt().matches("(?is).*(?:(?:只|仅)(?:允许)?(?:修改|改动|改|写入|新增)|only\\s+(?:modify|change|write)).*"))
                    .map(MutationObligation::pathRule).distinct().toList();
        }
        if (allowed.isEmpty() && !input.directSoftwareMode()) {
            var frozen = new DesignerMutationObligationExtractor().extractUsingFrozenScope(empty,
                    input.requirementText(), input.scopeIn(), input.scopeOut(), input.deliverables(), true);
            allowed = frozen.mutationObligations().stream().filter(item -> item.operation() == MutationOperation.WRITE)
                    .map(MutationObligation::pathRule).distinct().toList();
        }
        // A semantic direct-package scope is not an exhaustive file allowlist. The final
        // plan still enforces repository containment, forbidden paths and no deletion.
        var relations = VerifierPathPolicy.boundedRuleRelations();
        var forbidden = new DesignerAcceptanceStagePathPlanner().forbiddenPaths(empty, input.scopeOut());
        var problems = new ArrayList<Problem>();
        try {
            for (int index = 0; index < plan.stages().size(); index++) {
                var stage = plan.stages().get(index);
                check(stage.allowedPaths(), allowed, relations, "/stages/" + index + "/allowedPaths", problems);
                checkForbidden(stage.forbiddenPaths(), forbidden, relations, problems);
                if (stage.verifiers().stream().noneMatch(item -> "GIT_DIFF".equals(item.type())))
                    problems.add(PackageFrozenSafety.internal("PACKAGE_COMPILED_SCOPE_VERIFIER_MISSING", "编译后的阶段必须包含 GIT_DIFF 范围验收"));
                for (int verifierIndex = 0; verifierIndex < stage.verifiers().size(); verifierIndex++) {
                    var verifier = stage.verifiers().get(verifierIndex);
                    if (!"GIT_DIFF".equals(verifier.type())) continue;
                    check(verifier.allowedPaths(), allowed, relations,
                            "/stages/" + index + "/verifiers/" + verifierIndex + "/allowedPaths", problems);
                    checkForbidden(verifier.forbiddenPaths(), forbidden, relations, problems);
                    if (!Boolean.TRUE.equals(verifier.forbidDeletes())) problems.add(PackageFrozenSafety.internal(
                            "PACKAGE_COMPILED_DELETE_PERMISSION", "编译后的 GIT_DIFF 必须保留禁止删除约束"));
                }
            }
        } catch (IllegalArgumentException invalid) {
            return List.of(PackageFrozenSafety.internal("PACKAGE_SCOPE_PROOF_UNAVAILABLE", "路径规则无效，无法证明编译范围守恒"));
        }
        return List.copyOf(problems);
    }

    private static void checkForbidden(List<String> actual, List<String> frozen,
                                       VerifierPathPolicy.RuleRelations relations, List<Problem> problems) {
        for (var rule : frozen) {
            if (actual != null && actual.stream().anyMatch(bound -> relations.allowedRuleCovers(rule, bound))) continue;
            problems.add(PackageFrozenSafety.internal("PACKAGE_COMPILED_FORBIDDEN_SCOPE_LOST",
                    "编译后的阶段和 GIT_DIFF 必须保留冻结禁止路径及敏感文件保护：" + rule));
        }
    }

    private static void check(List<String> actual, List<String> frozen, VerifierPathPolicy.RuleRelations relations,
                              String pointer, List<Problem> problems) {
        if (actual == null || actual.isEmpty()) {
            problems.add(PackageFrozenSafety.internal("PACKAGE_COMPILED_SCOPE_EMPTY", "编译后的可写范围为空，无法证明范围守恒"));
            return;
        }
        for (int index = 0; index < actual.size(); index++) {
            var rule = actual.get(index);
            if (safeRule(VerifierPathPolicy.normalizePathRule(rule))
                    && (frozen.isEmpty() || frozen.stream().anyMatch(bound -> covered(rule, bound, relations)))) continue;
            problems.add(new Problem("PACKAGE_COMPILED_SCOPE_EXPANSION", pointer + "/" + index,
                    "设计路径不符合工作包范围：" + rule + "；请修正设计后重提", frozen,
                    ProblemClass.SECURITY, false, "所有编译路径均为冻结范围的子集", rule,
                    "修正对应交付物或阶段的路径，重提完整候选；保留需求覆盖，不修改用户限制，不用 ANY 绕过校验"));
        }
    }

    private static boolean covered(String rule, String bound, VerifierPathPolicy.RuleRelations relations) {
        String actual = VerifierPathPolicy.normalizePathRule(rule);
        String frozen = VerifierPathPolicy.normalizePathRule(bound);
        if (!safeRule(actual) || !safeRule(frozen)) return false;
        if (exactFile(frozen)) return actual.equals(frozen);
        return exactFile(actual) ? relations.allowedRuleCoversExactPath(actual, frozen)
                : relations.allowedRuleCovers(actual, frozen);
    }

    private static boolean safeRule(String rule) {
        return !rule.isBlank() && !rule.startsWith("/") && !rule.startsWith("~") && !rule.contains(":")
                && java.util.Arrays.stream(rule.split("/", -1)).noneMatch(part -> part.equals("..") || part.equals(".")
                        || part.equalsIgnoreCase(".git") || part.equalsIgnoreCase(".env")
                        || part.toLowerCase(java.util.Locale.ROOT).startsWith(".env."));
    }

    private static boolean exactFile(String rule) {
        return !rule.matches(".*[*?\\[\\]{}].*")
                && (rule.substring(rule.lastIndexOf('/') + 1).contains(".")
                || DesignerRepositoryPathSyntax.commonRootFile(rule.substring(rule.lastIndexOf('/') + 1)));
    }
}
