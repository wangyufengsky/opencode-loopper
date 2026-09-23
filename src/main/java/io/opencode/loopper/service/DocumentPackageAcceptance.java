package io.opencode.loopper.service;

import java.util.*;

/** Extra template obligations are checked against the same compiled plan; ordinary package policy is unchanged. */
final class DocumentPackageAcceptance {
    private DocumentPackageAcceptance() { }
    static List<PackageDesignCompilation.Problem> validate(PackageDesignCompilation.Input input,
            PackageDesignV2Document candidate, DesignerSemanticContracts.PackageCompilationPlanEnvelope plan) {
        if (!DocumentRequirementContext.document(input.requirementText()) && !SourceRequirementContext.source(input.requirementText())) return List.of();
        var sources = PackageRequirementSources.index(input.requirementText());
        var problems = new ArrayList<PackageDesignCompilation.Problem>();
        var coverage = coverage(candidate);
        for (String source : sources.keySet()) if (coverage.getOrDefault(source, List.of()).isEmpty())
            problems.add(problem("DOCUMENT_REQUIREMENT_SCENARIO_MISSING", "/sourceBindings",
                    "来源 " + source + " 必须关联至少一个可观察验收场景及其执行阶段，只有文字需求或交付物绑定不够"));
        if (candidate.stages().size() != plan.stages().size()) {
            problems.add(problem("DOCUMENT_COMPILED_STAGE_MAPPING_CHANGED", "/stages", "编译阶段与来源阶段对应关系不完整，不能继续执行"));
            return List.copyOf(problems);
        }
        for (int index = 0; index < plan.stages().size(); index++) {
            if (plan.stages().get(index).verifiers().stream().noneMatch(verifier -> "PROCESS".equals(verifier.type()) && "TEST".equals(verifier.processPurpose())))
                problems.add(problem("DOCUMENT_STAGE_TEST_REQUIRED", "/stages/" + index,
                        "需求开发阶段必须包含仓库原生 TEST 行为验证；构建、文件存在或评审意见不能替代测试"));
        }
        if (sources.containsKey(DocumentRequirementContext.FINAL_REGRESSION)) {
            int finalIndex = plan.stages().size() - 1;
            if (coverage.getOrDefault(DocumentRequirementContext.FINAL_REGRESSION, List.of()).stream()
                    .noneMatch(item -> item.stageIndex() == finalIndex))
                problems.add(problem("DOCUMENT_FINAL_REGRESSION_REQUIRED", "/sourceBindings",
                        "最终整体回归必须属于最后执行阶段，确保它在所有本期改动之后运行；保留跨包、受影响功能和核心流程断言"));
        }
        return List.copyOf(problems);
    }
    static Map<String, List<Scenario>> coverage(PackageDesignV2Document candidate) {
        var result = new LinkedHashMap<String, List<Scenario>>();
        for (var binding : candidate.sourceBindings()) {
            Set<String> refs = Set.copyOf(binding.candidateRefs());
            for (var scenario : candidate.scenarios()) {
                if (!refs.contains(scenario.key()) && scenario.requirementRefs().stream().noneMatch(refs::contains)) continue;
                for (int stage = 0; stage < candidate.stages().size(); stage++) {
                    if (!candidate.stages().get(stage).includes().contains(scenario.key())) continue;
                    var mapped = new Scenario(scenario.key(), scenario.title(), stage, candidate.stages().get(stage).key());
                    for (String source : binding.sourceRefs()) {
                        var values = result.computeIfAbsent(source, ignored -> new ArrayList<>());
                        if (!values.contains(mapped)) values.add(mapped);
                    }
                }
            }
        }
        var immutable = new LinkedHashMap<String, List<Scenario>>();
        result.forEach((key, value) -> immutable.put(key, List.copyOf(value)));
        return Collections.unmodifiableMap(immutable);
    }
    private static PackageDesignCompilation.Problem problem(String code, String pointer, String detail) {
        return new PackageDesignCompilation.Problem(code, pointer, detail, List.of(),
                PackageDesignCompilation.ProblemClass.CORRECTABLE, false,
                "冻结需求到可执行阶段及验收场景的完整覆盖", "来源映射或测试不足", "补齐对应场景、阶段和原生测试，重提完整候选；不得删减冻结需求");
    }
    record Scenario(String key, String title, int stageIndex, String stageKey) { }
}
