package io.opencode.loopper.service;

import static io.opencode.loopper.service.DesignerAcceptancePlanning.*;
import static io.opencode.loopper.service.DesignerSemanticContracts.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class DesignerAcceptanceFastPathResolverTest {
    private final DesignerAcceptanceFastPathResolver resolver = new DesignerAcceptanceFastPathResolver();

    @Test
    void resolvesNfkcWhitespaceCaseSemicolonsAndEarlierDependenciesWithoutFuzzyMatching() {
        Catalog catalog = catalog(List.of(
                stage("基础阶段", "实现基础", List.of("ＡＰＩ   成功", "src/main/App.java"), List.of()),
                stage("验证阶段", "验证失败", List.of("api 失败"), List.of("基础阶段"))),
                List.of(fact(0, FactKind.SCENARIO, "API 成功"),
                        fact(1, FactKind.DELIVERABLE, "src/main/App.java"),
                        fact(2, FactKind.SCENARIO, "API 失败")));

        var result = resolver.resolve(catalog, capabilities(0, 2));

        assertThat(result.outcome()).isEqualTo(DesignerAcceptanceFastPathResolver.Outcome.RESOLVED);
        assertThat(result.groupHints()).hasSize(2);
        assertThat(result.groupHints().get(0).factIndexes()).containsExactly(0, 1);
        assertThat(result.groupHints().get(1).factIndexes()).containsExactly(2);
        assertThat(result.groupHints().get(1).dependsOnHintIndexes()).containsExactly(0);
    }

    @Test
    void leavesUnknownExactReferenceForOneCompilerPassButDoesNotSubstringMatchIt() {
        Catalog catalog = catalog(List.of(stage("实现", "实现行为", List.of("支付成功场景"), List.of())),
                List.of(fact(0, FactKind.SCENARIO, "支付成功")));

        var result = resolver.resolve(catalog, capabilities(0));

        assertThat(result.outcome()).isEqualTo(DesignerAcceptanceFastPathResolver.Outcome.NEEDS_COMPILER);
        assertThat(result.unresolvedFactIndexes()).containsExactly(0);
        assertThat(result.routingReasons()).contains("UNKNOWN_FACT_REFERENCE:支付成功场景");
    }

    @Test
    void rejectsExtraneousUnknownReferenceWhenCompilerHasNoClosedFactHoleToFill() {
        Catalog catalog = catalog(List.of(stage("实现", "实现行为", List.of("支付成功", "不存在"), List.of())),
                List.of(fact(0, FactKind.SCENARIO, "支付成功")));

        var result = resolver.resolve(catalog, capabilities(0));

        assertThat(result.outcome()).isEqualTo(DesignerAcceptanceFastPathResolver.Outcome.DESIGN_INCOMPLETE);
        assertThat(result.routingReasons()).containsExactly("ACCEPTANCE_FACT_REFERENCE_INVALID");
    }

    @Test
    void rejectsDuplicateAcceptanceOwnershipAndForwardDependencyAsStructuralConflicts() {
        Catalog duplicate = catalog(List.of(
                stage("一", "一", List.of("成功"), List.of()),
                stage("二", "二", List.of("成功"), List.of("一"))),
                List.of(fact(0, FactKind.SCENARIO, "成功")));
        assertThat(resolver.resolve(duplicate, capabilities(0)).outcome())
                .isEqualTo(DesignerAcceptanceFastPathResolver.Outcome.DESIGN_INCOMPLETE);

        Catalog forward = catalog(List.of(
                stage("一", "一", List.of("成功"), List.of("二")),
                stage("二", "二", List.of("失败"), List.of())),
                List.of(fact(0, FactKind.SCENARIO, "成功"), fact(1, FactKind.SCENARIO, "失败")));
        assertThat(resolver.resolve(forward, capabilities(0, 1)).routingReasons())
                .containsExactly("ACCEPTANCE_STAGE_DEPENDENCY_NOT_PRIOR");
    }

    @Test
    void rejectsDuplicateStageNamesUnknownDependenciesCyclesAndStageCountOutsideClosedRange() {
        Catalog duplicateNames = catalog(List.of(
                stage("阶段Ａ", "一", List.of("成功"), List.of()),
                stage("阶段A", "二", List.of("失败"), List.of())),
                List.of(fact(0, FactKind.SCENARIO, "成功"), fact(1, FactKind.SCENARIO, "失败")));
        assertThat(resolver.resolve(duplicateNames, capabilities(0, 1)).routingReasons())
                .containsExactly("ACCEPTANCE_STAGE_TITLE_DUPLICATE");

        Catalog unknownDependency = catalog(List.of(
                stage("实现", "实现", List.of("成功"), List.of("不存在"))),
                List.of(fact(0, FactKind.SCENARIO, "成功")));
        assertThat(resolver.resolve(unknownDependency, capabilities(0)).routingReasons())
                .containsExactly("ACCEPTANCE_STAGE_DEPENDENCY_UNKNOWN");

        Catalog selfCycle = catalog(List.of(
                stage("实现", "实现", List.of("成功"), List.of("实现"))),
                List.of(fact(0, FactKind.SCENARIO, "成功")));
        assertThat(resolver.resolve(selfCycle, capabilities(0)).routingReasons())
                .containsExactly("ACCEPTANCE_STAGE_DEPENDENCY_NOT_PRIOR");

        assertThat(resolver.resolve(catalog(List.of(), List.of(fact(0, FactKind.SCENARIO, "成功"))),
                capabilities(0)).routingReasons()).containsExactly("ACCEPTANCE_STAGE_COUNT_INVALID");
        List<StageHint> sevenStages = java.util.stream.IntStream.rangeClosed(1, 7)
                .mapToObj(index -> stage("阶段 " + index, "目标 " + index,
                        List.of("场景 " + index), index == 1 ? List.of() : List.of("阶段 " + (index - 1))))
                .toList();
        List<Fact> sevenFacts = java.util.stream.IntStream.range(0, 7)
                .mapToObj(index -> fact(index, FactKind.SCENARIO, "场景 " + (index + 1))).toList();
        assertThat(resolver.resolve(catalog(sevenStages, sevenFacts), capabilities(0, 1, 2, 3, 4, 5, 6))
                .routingReasons()).containsExactly("ACCEPTANCE_STAGE_COUNT_INVALID");
    }

    @Test
    void permitsSharedDeliverablesButRequiresScenarioAndReviewFactsToHaveSingleOwners() {
        Catalog catalog = catalog(List.of(
                stage("实现", "实现", List.of("成功", "src/main/App.java"), List.of()),
                stage("评审", "评审", List.of("安全评审", "src/main/App.java"), List.of("实现"))),
                List.of(fact(0, FactKind.SCENARIO, "成功"),
                        fact(1, FactKind.DELIVERABLE, "src/main/App.java"),
                        fact(2, FactKind.REVIEW, "安全评审")));

        var result = resolver.resolve(catalog, capabilities(0));

        assertThat(result.outcome()).isEqualTo(DesignerAcceptanceFastPathResolver.Outcome.RESOLVED);
        assertThat(result.groupHints().get(0).factIndexes()).containsExactly(0, 1);
        assertThat(result.groupHints().get(1).factIndexes()).containsExactly(2, 1);
    }

    @Test
    void asksCompilerOnlyForAmbiguousClosedCapabilityAndRejectsMovingLockedFacts() {
        Catalog catalog = catalog(List.of(stage("实现", "实现行为", List.of("成功"), List.of())),
                List.of(fact(0, FactKind.SCENARIO, "成功")));
        CapabilityCatalog capabilities = new CapabilityCatalog(CONTRACT_VERSION_V6, List.of(
                capability(0, 0), capability(1, 0)), List.of());
        var resolution = resolver.resolve(catalog, capabilities);

        assertThat(resolution.outcome()).isEqualTo(DesignerAcceptanceFastPathResolver.Outcome.NEEDS_COMPILER);
        assertThat(resolution.unresolvedFactIndexes()).isEmpty();
        assertThat(resolution.ambiguousCapabilityFactIndexes()).containsExactly(0);
        assertThatThrownBy(() -> resolver.merge(resolution,
                new CompactAcceptanceDisambiguationPlan("非法移动",
                        List.of(new AcceptanceFactAssignment(0, 0)),
                        List.of(new AcceptanceCapabilityPreference(0, List.of(0))), null),
                catalog, capabilities)).isInstanceOfSatisfying(BadRequestException.class,
                        error -> assertThat(error.code()).isEqualTo("AMBIGUOUS_ACCEPTANCE_INTENT"));
    }

    @Test
    void fillsEveryUnresolvedFactOnceWithoutChangingTheLockedTopology() {
        Catalog catalog = catalog(List.of(
                stage("一", "一", List.of("成功"), List.of()),
                stage("二", "二", List.of("未知引用"), List.of("一"))),
                List.of(fact(0, FactKind.SCENARIO, "成功"), fact(1, FactKind.SCENARIO, "失败")));
        CapabilityCatalog capabilities = capabilities(0, 1);
        var resolution = resolver.resolve(catalog, capabilities);

        CompactAcceptanceBindingPlan merged = resolver.merge(resolution,
                new CompactAcceptanceDisambiguationPlan("已消歧",
                        List.of(new AcceptanceFactAssignment(1, 1)), List.of(), "交接"),
                catalog, capabilities);

        assertThat(merged.groupHints()).extracting(AcceptanceGroupHint::title).containsExactly("一", "二");
        assertThat(merged.groupHints().get(0).factIndexes()).containsExactly(0);
        assertThat(merged.groupHints().get(1).factIndexes()).containsExactly(1);
        assertThat(merged.groupHints().get(1).dependsOnHintIndexes()).containsExactly(0);
    }

    private static Catalog catalog(List<StageHint> stages, List<Fact> facts) {
        return new Catalog(CONTRACT_VERSION_V6, "WP-1", 1, "0".repeat(64), true,
                facts, stages, List.of());
    }

    private static StageHint stage(String title, String objective, List<String> included,
                                   List<String> dependencies) {
        return new StageHint(title, objective, included, dependencies, List.of(), List.of());
    }

    private static Fact fact(int index, FactKind kind, String title) {
        return new Fact(index, kind, title, "条件", "操作", "结果", "不变", null,
                "DS-L00" + (index + 1), title, "0".repeat(64));
    }

    private static CapabilityCatalog capabilities(int... facts) {
        return new CapabilityCatalog(CONTRACT_VERSION_V6,
                java.util.stream.IntStream.range(0, facts.length)
                        .mapToObj(index -> capability(index, facts[index])).toList(), List.of());
    }

    private static Capability capability(int index, int factIndex) {
        return new Capability(index, "FOCUSED_TEST", "测试 " + index,
                List.of("mvn", "-Dtest=Example" + index + "Test", "test"),
                List.of(factIndex), List.of("Example" + index + "Test"), true, false, 100);
    }
}
