package io.opencode.loopper.service;

import static io.opencode.loopper.service.DesignerAcceptancePlanning.*;
import static io.opencode.loopper.service.DesignerSemanticContracts.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

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
    void v7BindsEverySingleStageAcceptanceFactAndDropsOnlyUnlistedStageLabels() {
        Catalog catalog = new Catalog(CONTRACT_VERSION_V7, "WP-1", 1, "0".repeat(64), true,
                List.of(fact(0, FactKind.SCENARIO, "格式化结果正确"),
                        fact(1, FactKind.REVIEW, "中文措辞自然")),
                List.of(stage("阶段 1：本地格式化", "实现本地格式化",
                        List.of("格式化结果正确", "影响与交付", "本地格式化"), List.of())),
                List.of());

        var result = resolver.resolve(catalog, capabilities(0));

        assertThat(result.outcome()).isEqualTo(DesignerAcceptanceFastPathResolver.Outcome.RESOLVED);
        assertThat(result.unresolvedFactIndexes()).isEmpty();
        assertThat(result.groupHints()).singleElement().satisfies(group ->
                assertThat(group.factIndexes()).containsExactly(0, 1));
        assertThat(result.routingReasons())
                .contains("SINGLE_STAGE_FACT_BOUND:1",
                        "UNLISTED_STAGE_REFERENCE_DROPPED:影响与交付",
                        "UNLISTED_STAGE_REFERENCE_DROPPED:本地格式化");
    }

    @Test
    void v7FailsClosedWhenAStageReferenceMatchesDuplicateFrozenFacts() {
        Catalog catalog = new Catalog(CONTRACT_VERSION_V7, "WP-1", 1, "0".repeat(64), true,
                List.of(fact(0, FactKind.SCENARIO, "同名事实"),
                        fact(1, FactKind.REVIEW, "同名事实")),
                List.of(stage("实现", "实现", List.of("同名事实"), List.of())), List.of());

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
    void historicalV6KeepsAcceptingDuplicateCapabilityIndexes() {
        Catalog catalog = catalog(List.of(stage("实现", "实现行为", List.of("成功"), List.of())),
                List.of(fact(0, FactKind.SCENARIO, "成功")));
        CapabilityCatalog capabilities = new CapabilityCatalog(CONTRACT_VERSION_V6, List.of(
                capability(0, 0), capability(1, 0)), List.of());
        var resolution = resolver.resolve(catalog, capabilities);

        CompactAcceptanceBindingPlan merged = resolver.merge(resolution,
                new CompactAcceptanceDisambiguationPlan(null, List.of(),
                        List.of(new AcceptanceCapabilityPreference(0, List.of(0, 0))), null),
                catalog, capabilities);

        assertThat(merged.capabilityPreferences()).singleElement().satisfies(preference ->
                assertThat(preference.capabilityIndexes()).containsExactly(0, 0));
    }

    @Test
    void v7AvoidsCompilerWhenMultipleCoveringCapabilitiesHaveOneBusinessOptimum() {
        Catalog catalog = new Catalog(CONTRACT_VERSION_V7, "WP-1", 1, "0".repeat(64), true,
                List.of(fact(0, FactKind.SCENARIO, "成功")),
                List.of(stage("实现", "实现行为", List.of("成功"), List.of())), List.of());
        CapabilityCatalog capabilities = new CapabilityCatalog(CONTRACT_VERSION_V7, List.of(
                new Capability(0, "FOCUSED_TEST", "强确定性测试",
                        List.of("mvn", "-Dtest=StrongTest", "test"), List.of(0),
                        List.of("StrongTest"), true, false, 100),
                new Capability(1, "FOCUSED_TEST", "弱候选测试",
                        List.of("mvn", "-Dtest=WeakTest", "test"), List.of(0),
                        List.of("WeakTest"), true, false, 80)), List.of());

        var resolution = resolver.resolve(catalog, capabilities);

        assertThat(resolution.outcome()).isEqualTo(DesignerAcceptanceFastPathResolver.Outcome.RESOLVED);
        assertThat(resolution.ambiguousCapabilityFactIndexes()).isEmpty();
        assertThat(resolution.routingReasons()).contains("COMPILER_AVOIDED_UNIQUE_OPTIMUM");
        DesignerAcceptanceV7MeasurementRegistry.record("capability-resolution", java.util.Map.of(
                "v7UniqueOptimumRequiredCompilerCalls",
                resolution.outcome() == DesignerAcceptanceFastPathResolver.Outcome.NEEDS_COMPILER ? 1 : 0),
                Set.of("UNIQUE_OPTIMUM_MEASURED"));
    }

    @Test
    void v7TreatsDeterminismAsBusinessScoreAndReportsOnlyRealTies() {
        Catalog catalog = new Catalog(CONTRACT_VERSION_V7, "WP-1", 1, "0".repeat(64), true,
                List.of(fact(0, FactKind.SCENARIO, "成功")),
                List.of(stage("实现", "实现行为", List.of("成功"), List.of())), List.of());
        Capability deterministic = new Capability(0, "FOCUSED_TEST", "确定性测试",
                List.of("mvn", "-Dtest=StableTest", "test"), List.of(0),
                List.of("StableTest"), true, false, 100);
        Capability nondeterministic = new Capability(1, "FOCUSED_TEST", "非确定性候选",
                List.of("mvn", "-Dtest=UnstableTest", "test"), List.of(0),
                List.of("UnstableTest"), false, false, 100);

        var unique = resolver.resolve(catalog,
                new CapabilityCatalog(CONTRACT_VERSION_V7,
                        List.of(deterministic, nondeterministic), List.of()));
        var tied = resolver.resolve(catalog,
                new CapabilityCatalog(CONTRACT_VERSION_V7,
                        List.of(deterministic, new Capability(2, "FOCUSED_TEST", "同分确定性测试",
                                List.of("mvn", "-Dtest=PeerTest", "test"), List.of(0),
                                List.of("PeerTest"), true, false, 100)), List.of()));

        assertThat(unique.outcome()).isEqualTo(DesignerAcceptanceFastPathResolver.Outcome.RESOLVED);
        assertThat(unique.routingReasons()).contains("COMPILER_AVOIDED_UNIQUE_OPTIMUM");
        assertThat(unique.trueCapabilityTieCount()).isZero();
        assertThat(tied.outcome()).isEqualTo(DesignerAcceptanceFastPathResolver.Outcome.NEEDS_COMPILER);
        assertThat(tied.ambiguousCapabilityFactIndexes()).containsExactly(0);
        assertThat(tied.trueCapabilityTieCount()).isEqualTo(2);
        assertThat(tied.tiedCapabilityIndexesByFact()).containsEntry(0, List.of(0, 2));
        DesignerAcceptanceV7MeasurementRegistry.record("capability-resolution", java.util.Map.of(
                "v7DeterministicWinnerRequiredCompilerCalls",
                unique.outcome() == DesignerAcceptanceFastPathResolver.Outcome.NEEDS_COMPILER ? 1 : 0,
                "v7TrueTieRequiredCompilerCalls",
                tied.outcome() == DesignerAcceptanceFastPathResolver.Outcome.NEEDS_COMPILER ? 1 : 0,
                "v7TrueTieOptimalSolutions", tied.trueCapabilityTieCount()),
                Set.of("TRUE_TIE_MEASURED"));
    }

    @Test
    void v7RejectsWeakOrDuplicatePreferencesOutsideTheTrueTieSet() {
        Catalog catalog = new Catalog(CONTRACT_VERSION_V7, "WP-1", 1, "0".repeat(64), true,
                List.of(fact(0, FactKind.SCENARIO, "成功")),
                List.of(stage("实现", "实现行为", List.of("成功"), List.of())), List.of());
        CapabilityCatalog capabilities = new CapabilityCatalog(CONTRACT_VERSION_V7, List.of(
                new Capability(0, "FOCUSED_TEST", "同分 A", List.of("mvn", "-Dtest=ATest", "test"),
                        List.of(0), List.of("ATest"), true, false, 100),
                new Capability(1, "FOCUSED_TEST", "同分 B", List.of("mvn", "-Dtest=BTest", "test"),
                        List.of(0), List.of("BTest"), true, false, 100),
                new Capability(2, "FOCUSED_TEST", "较弱 C", List.of("mvn", "-Dtest=CTest", "test"),
                        List.of(0), List.of("CTest"), true, false, 80)), List.of());
        var resolution = resolver.resolve(catalog, capabilities);
        ObjectMapper json = new ObjectMapper();
        DesignerAcceptanceWorkflow workflow = new DesignerAcceptanceWorkflow(null, json,
                new AiOutputExtractor(json), null, new DesignerEvidenceIndexer(),
                new DesignerPackagePlanCompiler(new DesignerEvidenceIndexer()));

        assertThat(workflow.closedChoiceCapabilities(capabilities, resolution))
                .contains("closed-choice-0", "closed-choice-1")
                .doesNotContain("同分 A", "同分 B", "较弱 C", "command", "testTargets",
                        "ATest", "BTest", "CTest");

        for (List<Integer> invalid : List.of(List.of(2), List.of(0, 0))) {
            assertThatThrownBy(() -> resolver.merge(resolution,
                    new CompactAcceptanceDisambiguationPlan(null, List.of(),
                            List.of(new AcceptanceCapabilityPreference(0, invalid)), null),
                    catalog, capabilities))
                    .isInstanceOfSatisfying(BadRequestException.class,
                            error -> assertThat(error.code()).isEqualTo("AMBIGUOUS_ACCEPTANCE_INTENT"));
        }
    }

    @Test
    void v7FailsClosedInsteadOfCallingCompilerWhenTheOptimumWasNotExhaustivelyProven() {
        DesignerAcceptanceFastPathResolver bounded = new DesignerAcceptanceFastPathResolver(
                new DesignerAcceptanceCapabilitySolver(1));
        Catalog catalog = new Catalog(CONTRACT_VERSION_V7, "WP-1", 1, "0".repeat(64), true,
                List.of(fact(0, FactKind.SCENARIO, "成功")),
                List.of(stage("实现", "实现行为", List.of("成功"), List.of())), List.of());
        CapabilityCatalog capabilities = new CapabilityCatalog(CONTRACT_VERSION_V7,
                List.of(capability(0, 0), capability(1, 0)), List.of());

        var result = bounded.resolve(catalog, capabilities);

        assertThat(result.outcome()).isEqualTo(DesignerAcceptanceFastPathResolver.Outcome.DESIGN_INCOMPLETE);
        assertThat(result.routingReasons()).containsExactly("CAPABILITY_SOLVER_NON_EXHAUSTIVE");
    }

    @Test
    void v7RequiresPreferencesToIdentifyOneCompleteOptimalCapabilitySet() {
        Catalog catalog = new Catalog(CONTRACT_VERSION_V7, "WP-1", 1, "0".repeat(64), true,
                List.of(fact(0, FactKind.SCENARIO, "成功")),
                List.of(stage("实现", "实现行为", List.of("成功"), List.of())), List.of());
        CapabilityCatalog capabilities = new CapabilityCatalog(CONTRACT_VERSION_V7,
                List.of(capability(0, 0), capability(1, 0), capability(2, 0)), List.of());
        var resolution = new DesignerAcceptanceFastPathResolver.Resolution(
                DesignerAcceptanceFastPathResolver.Outcome.NEEDS_COMPILER,
                List.of(new AcceptanceGroupHint("实现", "实现行为", List.of(0), List.of())),
                List.of(), List.of(0), java.util.Map.of(0, List.of(0, 1, 2)),
                List.of(List.of(0, 1), List.of(0, 2), List.of(1, 2)), 3,
                List.of("AMBIGUOUS_CAPABILITY:0"), List.of());

        assertThatThrownBy(() -> resolver.merge(resolution,
                new CompactAcceptanceDisambiguationPlan(null, List.of(),
                        List.of(new AcceptanceCapabilityPreference(0, List.of(0))), null),
                catalog, capabilities))
                .isInstanceOfSatisfying(BadRequestException.class,
                        error -> assertThat(error.code()).isEqualTo("AMBIGUOUS_ACCEPTANCE_INTENT"));
    }

    @Test
    void v7RejectsOutOfRangeCapabilityPreference() {
        Catalog catalog = new Catalog(CONTRACT_VERSION_V7, "WP-1", 1, "0".repeat(64), true,
                List.of(fact(0, FactKind.SCENARIO, "成功")),
                List.of(stage("实现", "实现行为", List.of("成功"), List.of())), List.of());
        CapabilityCatalog capabilities = new CapabilityCatalog(CONTRACT_VERSION_V7,
                List.of(capability(0, 0), capability(1, 0)), List.of());
        var resolution = resolver.resolve(catalog, capabilities);

        assertThatThrownBy(() -> resolver.merge(resolution,
                new CompactAcceptanceDisambiguationPlan(null, List.of(),
                        List.of(new AcceptanceCapabilityPreference(0, List.of(99))), null),
                catalog, capabilities))
                .isInstanceOfSatisfying(BadRequestException.class,
                        error -> assertThat(error.code()).isEqualTo("AMBIGUOUS_ACCEPTANCE_INTENT"));
    }

    @Test
    void v7UsesTheUniqueGlobalCapabilitySetInsteadOfMistakingLocalChoicesForTies() {
        Catalog catalog = new Catalog(CONTRACT_VERSION_V7, "WP-1", 1, "0".repeat(64), true,
                List.of(fact(0, FactKind.SCENARIO, "成功"), fact(1, FactKind.SCENARIO, "失败")),
                List.of(stage("实现", "实现行为", List.of("成功", "失败"), List.of())), List.of());
        CapabilityCatalog capabilities = new CapabilityCatalog(CONTRACT_VERSION_V7, List.of(
                new Capability(0, "FOCUSED_TEST", "全局能力",
                        List.of("mvn", "-Dtest=FlowTest", "test"), List.of(0, 1),
                        List.of("FlowTest"), true, false, 100),
                capability(1, 0), capability(2, 1)), List.of());

        var resolution = resolver.resolve(catalog, capabilities);

        assertThat(resolution.outcome()).isEqualTo(DesignerAcceptanceFastPathResolver.Outcome.RESOLVED);
        assertThat(resolution.ambiguousCapabilityFactIndexes()).isEmpty();
        assertThat(resolution.trueCapabilityTieCount()).isZero();
        assertThat(resolution.routingReasons()).contains("COMPILER_AVOIDED_UNIQUE_OPTIMUM");
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

    @Test
    void v7PromptProjectionListsZeroBasedStageCandidatesForEveryUnresolvedFact() {
        Catalog catalog = new Catalog(CONTRACT_VERSION_V7, "WP-1", 1, "0".repeat(64), true,
                List.of(fact(0, FactKind.SCENARIO, "成功"), fact(1, FactKind.REVIEW, "安全评审")),
                List.of(stage("阶段 1", "实现", List.of("成功"), List.of()),
                        stage("阶段 2", "复核", List.of("未列出标签"), List.of("阶段 1"))),
                List.of());
        var resolution = resolver.resolve(catalog, capabilities(0));
        ObjectMapper json = new ObjectMapper();
        DesignerClosedChoiceContract contract = new DesignerClosedChoiceContract(json, new AiOutputExtractor(json));

        assertThat(contract.resolution(resolution))
                .contains("\"stageCandidates\":[{\"stageIndex\":0,\"title\":\"阶段 1\"",
                        "{\"stageIndex\":1,\"title\":\"阶段 2\"",
                        "\"factAssignmentCandidates\":[{\"factIndex\":1,\"allowedStageIndexes\":[0,1]}]");
    }

    @Test
    void v7MakesControlledScopeFactsReferableWithoutChangingHistoricalV6Resolution() {
        List<Fact> facts = List.of(fact(0, FactKind.SCENARIO, "成功"),
                fact(1, FactKind.SCOPE, "config/a.yml"));
        List<StageHint> stages = List.of(stage("实现", "实现", List.of("成功", "config/a.yml"), List.of()));
        Catalog v7 = new Catalog(CONTRACT_VERSION_V7, "WP-1", 1, "0".repeat(64), true,
                facts, stages, List.of());
        Catalog v6 = new Catalog(CONTRACT_VERSION_V6, "WP-1", 1, "0".repeat(64), true,
                facts, stages, List.of());

        var current = resolver.resolve(v7, capabilities(0));
        var historical = resolver.resolve(v6, capabilities(0));

        assertThat(current.outcome()).isEqualTo(DesignerAcceptanceFastPathResolver.Outcome.RESOLVED);
        assertThat(current.groupHints().getFirst().factIndexes()).containsExactly(0, 1);
        assertThat(historical.outcome()).isEqualTo(DesignerAcceptanceFastPathResolver.Outcome.DESIGN_INCOMPLETE);
        assertThat(historical.routingReasons()).containsExactly("ACCEPTANCE_FACT_REFERENCE_INVALID");
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
