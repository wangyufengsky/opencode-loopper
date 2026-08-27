package io.opencode.loopper.service;

import static io.opencode.loopper.service.DesignerAcceptancePlanning.CONTRACT_VERSION_V6;
import static io.opencode.loopper.service.DesignerAcceptancePlanning.CONTRACT_VERSION_V7;
import static io.opencode.loopper.service.DesignerSemanticContracts.CompactAcceptanceBindingPlan;
import static org.assertj.core.api.Assertions.assertThat;

import io.opencode.loopper.domain.ExecutionStrategy;
import io.opencode.loopper.domain.TestPolicy;
import io.opencode.loopper.persistence.DesignWorkPackageRow;
import io.opencode.loopper.verification.VerifierPathPolicy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class DesignerAcceptanceReadOnlyShadowTest {
    private static final Path REPORT = Path.of("target", "weak-model-compiler-v7-readonly-shadow.json");
    private static final String REQUIREMENT = "请修改 `src/main/java/example/Flow.java` 与 `config/flow.yml`，"
            + "并补齐 `src/test/java/example/FlowTest.java`。";
    private static final String DESIGN = """
            ## 目标与范围
            为本地 Flow 增加可观察的成功行为，不写外部系统。

            ## 影响与交付
            | 类型 | 相对路径或符号 | 说明 |
            | --- | --- | --- |
            | 修改生产代码 | src/main/java/example/Flow.java | 修改本地流程实现 |
            | 修改配置 | config/flow.yml | 修改本地流程配置 |
            | 新增测试 | src/test/java/example/FlowTest.java | 新增 FlowTest 聚焦测试 |

            ## 验收场景
            | 场景 | 前置或触发 | 操作 | 可观察结果 | 保持不变 |
            | --- | --- | --- | --- | --- |
            | Flow 成功行为 | 输入合法 | 调用 Flow | 返回成功结果 | 不写外部系统 |

            ## 验收约束
            FlowTest 必须独立通过，不依赖 Spring 上下文。

            ## 阶段与依赖
            | 阶段 | 目标 | 包含场景/评审/交付 | 前置阶段 |
            | --- | --- | --- | --- |
            | 实现 Flow | 实现并验证本地 Flow | Flow 成功行为；src/main/java/example/Flow.java；src/test/java/example/FlowTest.java | 无 |
            """;

    @Test
    void comparesV6AndV7FromTheSameFrozenInputWithoutPersistenceOrModelSessions() throws Exception {
        DesignerEvidenceIndexer indexer = new DesignerEvidenceIndexer();
        DesignerDesignFactExtractor factExtractor = new DesignerDesignFactExtractor(indexer);
        DesignerMutationObligationExtractor mutationExtractor = new DesignerMutationObligationExtractor();
        DesignerVerificationCapabilityRegistry capabilityRegistry = new DesignerVerificationCapabilityRegistry();
        DesignerAcceptanceFastPathResolver resolver = new DesignerAcceptanceFastPathResolver();
        DesignerAcceptancePlanCompiler compiler = new DesignerAcceptancePlanCompiler(
                new DesignerPackagePlanCompiler(indexer));

        DesignerAcceptancePlanning.Catalog v6Facts = factExtractor.extract("WP-1", 1, DESIGN, CONTRACT_VERSION_V6);
        DesignerAcceptancePlanning.Catalog v7Facts = mutationExtractor.extract(
                factExtractor.extract("WP-1", 1, DESIGN, CONTRACT_VERSION_V7),
                REQUIREMENT, List.of(), List.of("target/**"), List.of());
        WorkPackageRoleService.View v6Role = role(RolePackRegistry.ACCEPTANCE_V6_VERSION);
        WorkPackageRoleService.View v7Role = role(RolePackRegistry.VERSION);
        DesignerAcceptancePlanning.CapabilityCatalog v6Capabilities =
                capabilityRegistry.build(v6Facts, v6Role, DESIGN);
        DesignerAcceptancePlanning.CapabilityCatalog v7Capabilities =
                capabilityRegistry.build(v7Facts, v7Role, DESIGN);
        DesignerAcceptanceFastPathResolver.Resolution v6Resolution = resolver.resolve(v6Facts, v6Capabilities);
        DesignerAcceptanceFastPathResolver.Resolution v7Resolution = resolver.resolve(v7Facts, v7Capabilities);

        assertThat(v6Resolution.outcome()).isEqualTo(DesignerAcceptanceFastPathResolver.Outcome.RESOLVED);
        assertThat(v7Resolution.outcome()).isEqualTo(DesignerAcceptanceFastPathResolver.Outcome.RESOLVED);
        DesignerAcceptancePlanCompiler.Result v6 = compile(compiler, v6Facts, v6Capabilities, v6Role, v6Resolution);
        DesignerAcceptancePlanCompiler.Result v7 = compile(compiler, v7Facts, v7Capabilities, v7Role, v7Resolution);
        int v6Escapes = pathEscapes(v7Facts, v6);
        int v7Escapes = pathEscapes(v7Facts, v7);

        DesignerAcceptanceShadowEvaluator.Assessment v6Assessment = assessment(
                v6, v6Capabilities, v7Facts, v6Escapes);
        DesignerAcceptanceShadowEvaluator.Assessment v7Assessment = assessment(
                v7, v7Capabilities, v7Facts, v7Escapes);
        DesignerAcceptanceShadowEvaluator.Comparison comparison =
                new DesignerAcceptanceShadowEvaluator.Comparison(
                        "same-frozen-input-path-omission", true, Set.of(), v6Assessment, v7Assessment);
        DesignerAcceptanceShadowEvaluator.GateReport gate =
                new DesignerAcceptanceShadowEvaluator().evaluate(List.of(comparison));

        assertThat(v6.plan().status()).isEqualTo("COMPILED");
        assertThat(v7.plan().status()).isEqualTo("COMPILED");
        assertThat(v6Escapes).isEqualTo(1);
        assertThat(v7Escapes).isZero();
        assertThat(v7.mutationConservation().resolvedCount())
                .isEqualTo(v7.mutationConservation().obligationCount());
        assertThat(v7.plan().stages().getFirst().allowedPaths()).contains("config/flow.yml");
        assertThat(v6.plan().stages().getFirst().allowedPaths()).doesNotContain("config/flow.yml");
        assertThat(v7Assessment.quality().focusedTestCovered())
                .isEqualTo(v6Assessment.quality().focusedTestCovered());
        assertThat(gate.passed()).isTrue();
        DesignerAcceptanceV7MeasurementRegistry.record("same-input-production-pipeline", Map.ofEntries(
                Map.entry("v6Compiled", gate.v6Compiled()),
                Map.entry("v7Compiled", gate.v7Compiled()),
                Map.entry("v6Executable", gate.v6Executable()),
                Map.entry("v7Executable", gate.v7Executable()),
                Map.entry("v6CompilerCalls", gate.v6CompilerModelCalls()),
                Map.entry("v7CompilerCalls", gate.v7CompilerModelCalls()),
                Map.entry("v6Redesigns", gate.v6FullRedesigns()),
                Map.entry("v7Redesigns", gate.v7FullRedesigns()),
                Map.entry("v7PathEscapes", gate.knownPathEscapes()),
                Map.entry("v7DangerousAutoAuthorizations", gate.dangerousAutoAuthorizations()),
                Map.entry("v6JudgeOnly", v6Assessment.quality().judgeOnlyCount()),
                Map.entry("v7JudgeOnly", v7Assessment.quality().judgeOnlyCount()),
                Map.entry("v6FocusedRequired", v6Assessment.quality().focusedTestRequired()),
                Map.entry("v6FocusedCovered", v6Assessment.quality().focusedTestCovered()),
                Map.entry("v7FocusedRequired", v7Assessment.quality().focusedTestRequired()),
                Map.entry("v7FocusedCovered", v7Assessment.quality().focusedTestCovered())),
                Set.of("PRODUCTION_PIPELINE", "SAME_FROZEN_INPUT"));

        ObjectMapper json = new ObjectMapper();
        Files.createDirectories(REPORT.getParent());
        ReadOnlyShadowReport report = new ReadOnlyShadowReport(true, false,
                "authoritative measurement for one same-input target case, not complete release qualification; "
                        + "measured by the production fact, mutation, capability, resolver, and compiler pipeline; "
                        + "no persistence or model session",
                gate, comparison);
        Files.write(REPORT, json.writerWithDefaultPrettyPrinter().writeValueAsBytes(report));
        assertThat(Files.readString(REPORT))
                .doesNotContain(REQUIREMENT, "config/flow.yml", "src/", "frontend/", "docs/", "scripts/",
                        "/Users/", "/home/", "/tmp/", "modelOutput", "sessionId", "externalSessionId")
                .doesNotMatch("(?s).*[A-Za-z]:\\\\.*")
                .doesNotMatch("(?s).*[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-[89abAB]"
                        + "[0-9a-fA-F]{3}-[0-9a-fA-F]{12}.*");
    }

    private static DesignerAcceptancePlanCompiler.Result compile(
            DesignerAcceptancePlanCompiler compiler, DesignerAcceptancePlanning.Catalog facts,
            DesignerAcceptancePlanning.CapabilityCatalog capabilities, WorkPackageRoleService.View role,
            DesignerAcceptanceFastPathResolver.Resolution resolution) {
        return compiler.compile(workPackage(), DESIGN, facts, capabilities,
                new CompactAcceptanceBindingPlan("shadow", resolution.groupHints(), List.of(), null),
                role, List.of(), List.of("target/**"), List.of(), 6, true);
    }

    private static DesignerAcceptanceShadowEvaluator.Assessment assessment(
            DesignerAcceptancePlanCompiler.Result result,
            DesignerAcceptancePlanning.CapabilityCatalog capabilities,
            DesignerAcceptancePlanning.Catalog v7Facts, int escapes) {
        int acceptance = result.scenarios().size();
        int judgeOnly = (int) result.scenarios().stream()
                .filter(item -> item.coverage() == DesignerAcceptancePlanning.CoverageMode.JUDGE).count();
        int focusedCovered = (int) result.scenarios().stream()
                .filter(item -> item.coverage() == DesignerAcceptancePlanning.CoverageMode.AUTOMATED).count();
        int eligible = (int) v7Facts.mutationObligations().stream()
                .filter(DesignerAcceptanceReadOnlyShadowTest::eligible).count();
        int conserved = Math.max(0, eligible - escapes);
        int pathRules = result.plan().stages().stream().mapToInt(stage -> stage.allowedPaths().size()).sum();
        Set<String> gaps = result.plan().designGaps().stream().map(gap -> gap.code().name())
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        return new DesignerAcceptanceShadowEvaluator.Assessment(
                new DesignerAcceptanceShadowEvaluator.Quality("COMPILED".equals(result.plan().status()),
                        "COMPILED".equals(result.plan().status()) && escapes == 0,
                        acceptance, judgeOnly, acceptance - judgeOnly, focusedCovered),
                new DesignerAcceptanceShadowEvaluator.Cost(0, 0),
                new DesignerAcceptanceShadowEvaluator.Safety(eligible, conserved, escapes, 0, 0, 0),
                new DesignerAcceptanceShadowEvaluator.Shape(pathRules, capabilities.capabilities().size(), gaps),
                Set.of());
    }

    private static int pathEscapes(DesignerAcceptancePlanning.Catalog v7Facts,
                                   DesignerAcceptancePlanCompiler.Result compiled) {
        VerifierPathPolicy.RuleRelations relations = VerifierPathPolicy.boundedRuleRelations();
        return (int) v7Facts.mutationObligations().stream().filter(DesignerAcceptanceReadOnlyShadowTest::eligible)
                .filter(obligation -> compiled.plan().stages().stream().flatMap(stage -> stage.allowedPaths().stream())
                        .noneMatch(rule -> covers(relations, obligation, rule))).count();
    }

    private static boolean covers(VerifierPathPolicy.RuleRelations relations,
                                  DesignerAcceptancePlanning.MutationObligation obligation, String rule) {
        return obligation.pathKind() == DesignerAcceptancePlanning.MutationPathKind.EXACT_PATH
                ? relations.allowedRuleCoversExactPath(obligation.pathRule(), rule)
                : relations.allowedRuleCovers(obligation.pathRule(), rule);
    }

    private static boolean eligible(DesignerAcceptancePlanning.MutationObligation obligation) {
        return obligation.operation() == DesignerAcceptancePlanning.MutationOperation.WRITE
                || obligation.operation() == DesignerAcceptancePlanning.MutationOperation.MOVE_DESTINATION;
    }

    private static WorkPackageRoleService.View role(String version) {
        return new WorkPackageRoleService.View("software-java", version,
                ExecutionStrategy.OPEN_CODE_IMPLEMENTATION, TestPolicy.REQUIRED, List.of("java"));
    }

    private static DesignWorkPackageRow workPackage() {
        return new DesignWorkPackageRow("row-shadow", "session-shadow", "requirement-shadow", "decomposition-shadow",
                "WP-1", 1, "Flow", "实现 Flow", "[]", "[]", "[]", "[]", "[]", "[]",
                "COMPILING", null, null, "design-shadow", 1, 0, 0, null, null, null, null,
                null, 0, null, null, "2026-08-27T00:00:00Z", "2026-08-27T00:00:00Z", 0);
    }

    record ReadOnlyShadowReport(boolean authoritativeMeasurement, boolean completeQualification, String boundary,
                                DesignerAcceptanceShadowEvaluator.GateReport measurement,
                                DesignerAcceptanceShadowEvaluator.Comparison comparison) { }
}
