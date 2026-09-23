package io.opencode.loopper.service;

import io.opencode.loopper.domain.LoopSpec;
import io.opencode.loopper.verification.VerifierPathPolicy;
import java.util.*;
import static io.opencode.loopper.service.DesignerSemanticContracts.*;

/** Frozen test roots and whole-module regression are server obligations, independent of model declarations. */
final class SourcePackageAcceptance {
    private SourcePackageAcceptance() { }
    static List<PackageDesignCompilation.Problem> validate(PackageDesignCompilation.Input input, PackageCompilationPlanEnvelope plan) {
        if (!SourceRequirementContext.source(input.requirementText())) return List.of();
        var modules = SourceRequirementContext.modules(input.requirementText());
        var bounds = modules.stream().flatMap(m -> java.util.stream.Stream.concat(m.testRoots().stream(), m.fixtureRoots().stream()))
                .map(r -> r + "/**").toList();
        var relations = VerifierPathPolicy.boundedRuleRelations();
        var problems = new ArrayList<PackageDesignCompilation.Problem>();
        for (int i = 0; i < plan.stages().size(); i++) {
            var stage = plan.stages().get(i);
            var paths = new LinkedHashSet<>(stage.allowedPaths());
            stage.verifiers().stream().filter(v -> v.type().equals("GIT_DIFF")).forEach(v -> paths.addAll(v.allowedPaths()));
            for (var path : paths) if (bounds.stream().noneMatch(bound -> relations.allowedRuleCovers(path, bound))) {
                problems.add(problem("SOURCE_TEST_DESIGN_RANGE", "/stages/" + i, "设计的写入路径不属于冻结测试或夹具目录：" + path));
            }
        }
        if (plan.stages().stream().anyMatch(s -> s.verifiers().size() > 32))
            problems.add(problem("SOURCE_TEST_REGRESSION_CAPACITY", "/stages", "整体回归模块超过单阶段验证容量，需要拆分测试范围后重新设计"));
        return List.copyOf(problems);
    }
    static PackageCompilationPlanEnvelope withRegression(PackageDesignCompilation.Input input, PackageCompilationPlanEnvelope plan) {
        if (!SourceRequirementContext.source(input.requirementText()) || plan.stages().isEmpty()) return plan;
        var stages = new ArrayList<>(plan.stages());
        for (int i = 0; i < stages.size(); i++) {
            var stage = stages.get(i);
            // Existing tests and final regression may legitimately need no file changes.
            // Keep every scope/criterion while preventing an implementation-only diff default.
            var checks = new ArrayList<>(stage.verifiers().stream().map(SourcePackageAcceptance::testScope).toList());
            if (checks.stream().noneMatch(v -> v.type().equals("GIT_DIFF")))
                checks.add(new LoopSpec.VerifierSpec("GIT_DIFF", List.of(), null, false, stage.allowedPaths(), stage.forbiddenPaths(), true));
            stages.set(i, new PlannedStage(stage.objective(), stage.allowedPaths(), stage.forbiddenPaths(), stage.deliverables(),
                    checks, stage.verificationRuntime(), stage.implementationKind(), stage.workPackageId()));
        }
        if (!PackageRequirementSources.index(input.requirementText()).containsKey(DocumentRequirementContext.FINAL_REGRESSION))
            return new PackageCompilationPlanEnvelope(plan.contractVersion(), plan.status(), plan.summary(), stages, plan.evidenceMappings(),
                    plan.handoffSummary(), plan.designGaps());
        var last = stages.getLast();
        var verifiers = new ArrayList<>(last.verifiers());
        var criteria = last.verifiers().stream().filter(v -> v.type().equals("PROCESS") && "TEST".equals(v.processPurpose()))
                .flatMap(v -> v.criterionIds().stream()).distinct().toList();
        for (var module : SourceRequirementContext.modules(input.requirementText())) {
            if (verifiers.stream().anyMatch(v -> v.type().equals("PROCESS") && "TEST".equals(v.processPurpose()) && v.command().equals(module.command()))) continue;
            verifiers.add(new LoopSpec.VerifierSpec("PROCESS", module.command(), null, null, null, null, null,
                    null, null, null, null, null, null, null, null, null, null, null, null,
                    criteria, "TEST", module.testRoots()));
        }
        stages.set(stages.size() - 1, new PlannedStage(last.objective(), last.allowedPaths(), last.forbiddenPaths(), last.deliverables(),
                verifiers, last.verificationRuntime(), last.implementationKind(), last.workPackageId()));
        return new PackageCompilationPlanEnvelope(plan.contractVersion(), plan.status(), plan.summary(), stages, plan.evidenceMappings(),
                plan.handoffSummary(), plan.designGaps());
    }
    private static LoopSpec.VerifierSpec testScope(LoopSpec.VerifierSpec v) {
        if (!v.type().equals("GIT_DIFF")) return v;
        return new LoopSpec.VerifierSpec(v.type(), v.command(), v.path(), false, v.allowedPaths(), v.forbiddenPaths(), true,
                v.outputContains(), v.url(), v.httpMethod(), v.expectedStatus(), v.jsonPath(), v.expectedValue(), v.matchMode(),
                v.expectedContent(), v.expectedSha256(), v.sql(), v.expectedRowCount(), v.assertions(), v.criterionIds(),
                v.processPurpose(), v.testTargets(), v.documentAssertions(), v.tabularAssertions());
    }
    private static PackageDesignCompilation.Problem problem(String code, String pointer, String detail) {
        return new PackageDesignCompilation.Problem(code, pointer, detail, List.of(),
                PackageDesignCompilation.ProblemClass.CORRECTABLE, false,
                "冻结测试范围与完整模块回归", "设计范围或容量不满足", "修正测试交付范围或拆分阶段；不得修改业务源码或构建配置");
    }
}
