package io.opencode.loopper.service;

import static io.opencode.loopper.service.DesignerAcceptancePlanning.*;
import static io.opencode.loopper.service.DesignerSemanticContracts.*;

import io.opencode.loopper.domain.ImplementationKind;
import io.opencode.loopper.persistence.DesignWorkPackageRow;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Solves fact-to-capability coverage, then lowers it through the existing authoritative package compiler. */
final class DesignerAcceptancePlanCompiler {
    private static final long NODE_LIMIT = 100_000;
    private final DesignerPackagePlanCompiler packageCompiler;
    private final DesignerAcceptanceStageEvidenceBinder evidenceBinder = new DesignerAcceptanceStageEvidenceBinder();
    private final DesignerAcceptanceStagePathPlanner stagePathPlanner = new DesignerAcceptanceStagePathPlanner();
    private final MutationConservationPolicy mutationConservationPolicy = new MutationConservationPolicy();

    DesignerAcceptancePlanCompiler(DesignerPackagePlanCompiler packageCompiler) {
        this.packageCompiler = packageCompiler;
    }

    Result compile(DesignWorkPackageRow workPackage, String design, Catalog facts,
                   CapabilityCatalog capabilities, CompactAcceptanceBindingPlan input,
                   WorkPackageRoleService.View role, List<String> scopeIn, List<String> scopeOut,
                   List<String> deliverables, int stageLimit, boolean directSoftwareMode) {
        CompactAcceptanceBindingPlan binding = input.normalized();
        List<Fact> acceptanceFacts = facts.facts().stream()
                .filter(fact -> fact.kind() == FactKind.SCENARIO || fact.kind() == FactKind.REVIEW).toList();
        if (acceptanceFacts.isEmpty()) {
            throw new BadRequestException("MISSING_ACCEPTANCE_INTENT", "No acceptance facts are available to bind");
        }
        List<Group> groups = groups(binding, facts, acceptanceFacts);
        if (groups.size() > stageLimit) {
            throw new BadRequestException(directSoftwareMode ? "LARGE_TASK_MODE_REQUIRED"
                    : "COMPILER_PLAN_STAGE_COUNT_INVALID", "Acceptance groups exceed the package Stage limit");
        }
        ensureAcyclic(groups);
        List<CompactStage> stages = new ArrayList<>();
        List<List<String>> justifiedMutationPaths = new ArrayList<>();
        List<ScenarioView> scenarioViews = new ArrayList<>();
        List<String> normalizations = new ArrayList<>();
        long explored = 0;
        boolean fallback = false;
        LinkedHashSet<Integer> selectedCapabilityIndexes = new LinkedHashSet<>();
        List<Integer> allUncovered = new ArrayList<>();
        List<Capability> independentRequired = capabilities.capabilities().stream()
                .filter(Capability::mandatory).filter(capability -> capability.coversFactIndexes().isEmpty()).toList();
        for (int groupIndex = 0; groupIndex < groups.size(); groupIndex++) {
            Group group = groups.get(groupIndex);
            SolveResult solved = solve(group.acceptanceFactIndexes(), capabilities.capabilities(), binding);
            List<Capability> selected = new ArrayList<>(solved.selected());
            if (groupIndex == groups.size() - 1) {
                independentRequired.stream().filter(capability -> selected.stream()
                        .noneMatch(existing -> existing.index() == capability.index())).forEach(selected::add);
            }
            DesignerAcceptanceStagePathPlanner.Selection allowedPathSelection = stagePathPlanner.select(
                    facts, group.materialFactIndexes(), scopeIn, role);
            List<String> allowedPaths = allowedPathSelection.paths();
            List<String> stageDeliverables = stagePathPlanner.deliverables(
                    facts, group.materialFactIndexes(), deliverables);
            ImplementationKind kind = stagePathPlanner.implementationKind(role, allowedPaths);
            Capability stageGate = javaProductionGate(group, facts, capabilities.capabilities(), selected, kind);
            if (solved.uncovered().isEmpty() && kind == ImplementationKind.JAVA_PRODUCTION
                    && selected.stream().noneMatch(DesignerAcceptancePlanCompiler::focused)
                    && stageGate == null) {
                return incompleteStageGate(workPackage, design, facts, capabilities, binding, group,
                        explored + solved.exploredNodes(), fallback || solved.fallbackUsed(),
                        selectedCapabilityIndexes.size(), scenarioViews, stageLimit, directSoftwareMode);
            }
            explored += solved.exploredNodes();
            fallback |= solved.fallbackUsed();
            selected.stream().map(Capability::index).forEach(selectedCapabilityIndexes::add);
            if (stageGate != null) selectedCapabilityIndexes.add(stageGate.index());
            allUncovered.addAll(solved.uncovered());
            stages.add(stage(group, facts, selected, stageGate, allowedPaths, scopeOut,
                    stageDeliverables, kind, scenarioViews));
            justifiedMutationPaths.add(allowedPathSelection.justifiedPaths());
        }
        if (!allUncovered.isEmpty()) {
            return incomplete(workPackage, design, facts, capabilities, binding, allUncovered,
                    explored, fallback, selectedCapabilityIndexes.size(), scenarioViews, stageLimit, directSoftwareMode);
        }
        MutationConservationPolicy.Evaluation conservation = mutationConservationPolicy.evaluate(
                facts, stages, justifiedMutationPaths);
        if (!conservation.passed()) {
            return incompleteMutation(workPackage, design, facts, capabilities, binding, conservation,
                    explored, fallback, selectedCapabilityIndexes.size(), scenarioViews, stageLimit,
                    directSoftwareMode);
        }
        CompactPackageCompilationPlan compact = new CompactPackageCompilationPlan("COMPILED", binding.summary(),
                stages, binding.handoffSummary(), List.of());
        DesignerPackagePlanCompiler.Result lowered = packageCompiler.compile(
                workPackage, design, compact, stageLimit, directSoftwareMode);
        normalizations.addAll(lowered.normalizations());
        if (!independentRequired.isEmpty()) normalizations.add("INDEPENDENT_REQUIRED_CAPABILITIES_BOUND");
        if (stages.stream().anyMatch(stage -> stage.implementationKind() == ImplementationKind.JAVA_PRODUCTION
                && stage.evidence().stream().anyMatch(item -> "FOCUSED_TEST".equals(item.kind())
                && item.covers().isEmpty()))) normalizations.add("JAVA_PRODUCTION_STAGE_GATE_BOUND");
        SolverDiagnostics diagnostics = new SolverDiagnostics(fallback
                ? "DETERMINISTIC_GREEDY_2OPT" : "EXACT_BRANCH_AND_BOUND", explored, fallback,
                acceptanceFacts.size(), capabilities.capabilities().size(), selectedCapabilityIndexes.size(),
                List.copyOf(allUncovered), normalizations);
        return new Result(lowered.plan(), List.copyOf(normalizations), diagnostics,
                List.copyOf(scenarioViews), conservation);
    }

    private Result incompleteMutation(DesignWorkPackageRow workPackage, String design, Catalog facts,
                                      CapabilityCatalog capabilities, CompactAcceptanceBindingPlan binding,
                                      MutationConservationPolicy.Evaluation conservation,
                                      long explored, boolean fallback, int selectedCount,
                                      List<ScenarioView> scenarioViews, int stageLimit,
                                      boolean directSoftwareMode) {
        MutationConservationPolicy.Unresolved first = conservation.unresolved().getFirst();
        String detail = bounded(first.reason() + "：" + first.pathRule(), 2_000);
        CompactPackageCompilationPlan incomplete = new CompactPackageCompilationPlan(
                "DESIGN_INCOMPLETE", binding.summary(), List.of(), binding.handoffSummary(),
                List.of(new DesignGap(first.code(), detail)));
        DesignerPackagePlanCompiler.Result lowered = packageCompiler.compile(
                workPackage, design, incomplete, stageLimit, directSoftwareMode);
        List<String> normalizations = new ArrayList<>(lowered.normalizations());
        normalizations.add("MUTATION_PATH_CONSERVATION_BLOCKED");
        SolverDiagnostics diagnostics = new SolverDiagnostics(fallback
                ? "DETERMINISTIC_GREEDY_2OPT" : "EXACT_BRANCH_AND_BOUND", explored, fallback,
                facts.facts().stream().filter(fact -> fact.kind() == FactKind.SCENARIO
                        || fact.kind() == FactKind.REVIEW).toList().size(),
                capabilities.capabilities().size(), selectedCount, List.of(), normalizations);
        return new Result(lowered.plan(), List.copyOf(normalizations), diagnostics,
                List.copyOf(scenarioViews), conservation);
    }

    private Result incomplete(DesignWorkPackageRow workPackage, String design, Catalog facts,
                              CapabilityCatalog capabilities, CompactAcceptanceBindingPlan binding,
                              List<Integer> uncovered, long explored, boolean fallback, int selectedCount,
                              List<ScenarioView> scenarioViews, int stageLimit, boolean directSoftwareMode) {
        String titles = uncovered.stream().map(index -> fact(facts, index).title())
                .distinct().collect(java.util.stream.Collectors.joining("、"));
        String detail = bounded("服务端闭集验证能力无法覆盖以下设计事实：" + titles, 2_000);
        CompactPackageCompilationPlan incomplete = new CompactPackageCompilationPlan(
                "DESIGN_INCOMPLETE", binding.summary(), List.of(), binding.handoffSummary(),
                List.of(new DesignGap(DesignGapCode.VERIFICATION_CAPABILITY_UNAVAILABLE, detail)));
        DesignerPackagePlanCompiler.Result lowered = packageCompiler.compile(
                workPackage, design, incomplete, stageLimit, directSoftwareMode);
        List<String> normalizations = new ArrayList<>(lowered.normalizations());
        normalizations.add("SERVER_DERIVED_DESIGN_INCOMPLETE");
        SolverDiagnostics diagnostics = new SolverDiagnostics(fallback
                ? "DETERMINISTIC_GREEDY_2OPT" : "EXACT_BRANCH_AND_BOUND", explored, fallback,
                facts.facts().stream().filter(fact -> fact.kind() == FactKind.SCENARIO
                        || fact.kind() == FactKind.REVIEW).toList().size(),
                capabilities.capabilities().size(), selectedCount, List.copyOf(uncovered), normalizations);
        return new Result(lowered.plan(), List.copyOf(normalizations), diagnostics,
                List.copyOf(scenarioViews), MutationConservationPolicy.Evaluation.notEvaluated(facts));
    }

    private Result incompleteStageGate(DesignWorkPackageRow workPackage, String design, Catalog facts,
                                       CapabilityCatalog capabilities, CompactAcceptanceBindingPlan binding,
                                       Group group, long explored, boolean fallback, int selectedCount,
                                       List<ScenarioView> scenarioViews, int stageLimit, boolean directSoftwareMode) {
        String detail = bounded("Java 生产阶段“" + group.objective()
                + "”没有可唯一绑定的聚焦 Maven/Gradle 测试门禁", 2_000);
        CompactPackageCompilationPlan incomplete = new CompactPackageCompilationPlan(
                "DESIGN_INCOMPLETE", binding.summary(), List.of(), binding.handoffSummary(),
                List.of(new DesignGap(DesignGapCode.VERIFICATION_CAPABILITY_UNAVAILABLE, detail)));
        DesignerPackagePlanCompiler.Result lowered = packageCompiler.compile(
                workPackage, design, incomplete, stageLimit, directSoftwareMode);
        List<String> normalizations = new ArrayList<>(lowered.normalizations());
        normalizations.add("SERVER_DERIVED_DESIGN_INCOMPLETE");
        SolverDiagnostics diagnostics = new SolverDiagnostics(fallback
                ? "DETERMINISTIC_GREEDY_2OPT" : "EXACT_BRANCH_AND_BOUND", explored, fallback,
                facts.facts().stream().filter(fact -> fact.kind() == FactKind.SCENARIO
                        || fact.kind() == FactKind.REVIEW).toList().size(),
                capabilities.capabilities().size(), selectedCount, group.acceptanceFactIndexes(), normalizations);
        return new Result(lowered.plan(), List.copyOf(normalizations), diagnostics,
                List.copyOf(scenarioViews), MutationConservationPolicy.Evaluation.notEvaluated(facts));
    }

    private CompactStage stage(Group group, Catalog catalog, List<Capability> selected, Capability stageGate,
                               List<String> allowedPaths, List<String> scopeOut, List<String> deliverables,
                               ImplementationKind kind,
                               List<ScenarioView> views) {
        List<String> effectiveForbiddenPaths = stagePathPlanner.forbiddenPaths(catalog, scopeOut);
        DesignerAcceptanceStageEvidenceBinder.Binding binding = evidenceBinder.bind(
                group.acceptanceFactIndexes(), catalog, selected, stageGate, allowedPaths, effectiveForbiddenPaths);
        views.addAll(binding.views());
        return new CompactStage(group.objective(), kind, allowedPaths, effectiveForbiddenPaths, deliverables,
                binding.criteria(), binding.evidence(), null);
    }

    private SolveResult solve(List<Integer> requiredFacts, List<Capability> all,
                              CompactAcceptanceBindingPlan binding) {
        LinkedHashSet<Integer> required = new LinkedHashSet<>(requiredFacts);
        List<Capability> candidates = all.stream()
                .filter(capability -> capability.coversFactIndexes().stream().anyMatch(required::contains))
                .sorted(capabilityComparator(binding, required)).toList();
        LinkedHashSet<Integer> forcedIndexes = new LinkedHashSet<>();
        for (Capability candidate : candidates) if (candidate.mandatory()) forcedIndexes.add(candidate.index());
        Search search = new Search(required, candidates, forcedIndexes);
        search.run();
        if (search.best != null) return search.best;
        return greedy(required, candidates, forcedIndexes, search.explored);
    }

    private static SolveResult greedy(Set<Integer> required, List<Capability> candidates,
                                      Set<Integer> forcedIndexes, long explored) {
        LinkedHashSet<Integer> selected = new LinkedHashSet<>(forcedIndexes);
        LinkedHashSet<Integer> covered = covered(candidates, selected);
        while (!covered.containsAll(required)) {
            Capability best = candidates.stream().filter(capability -> !selected.contains(capability.index()))
                    .max(Comparator.comparingInt(capability -> newCoverage(capability, required, covered) * 1_000
                            + capability.strength())).orElse(null);
            if (best == null || newCoverage(best, required, covered) == 0) break;
            selected.add(best.index());
            covered.addAll(best.coversFactIndexes());
        }
        List<Capability> result = candidates.stream().filter(capability -> selected.contains(capability.index())).toList();
        List<Integer> uncovered = required.stream().filter(index -> !covered.contains(index)).toList();
        return new SolveResult(result, uncovered, explored, true);
    }

    private static Comparator<Capability> capabilityComparator(CompactAcceptanceBindingPlan binding,
                                                                Set<Integer> requiredFacts) {
        LinkedHashMap<Integer, Integer> preference = new LinkedHashMap<>();
        int ordinal = 0;
        for (AcceptanceCapabilityPreference item : binding.capabilityPreferences()) {
            if (item.factIndex() == null || !requiredFacts.contains(item.factIndex())) continue;
            for (Integer index : item.capabilityIndexes()) preference.putIfAbsent(index, ordinal++);
        }
        return Comparator.<Capability>comparingInt(capability -> preference.getOrDefault(capability.index(), 10_000))
                .thenComparing(Capability::deterministic, Comparator.reverseOrder())
                .thenComparing(Comparator.comparingInt(Capability::strength).reversed())
                .thenComparingInt(Capability::index);
    }

    private List<Group> groups(CompactAcceptanceBindingPlan binding, Catalog catalog, List<Fact> acceptanceFacts) {
        LinkedHashSet<Integer> validAcceptance = acceptanceFacts.stream().map(Fact::index)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        LinkedHashSet<Integer> validFacts = catalog.facts().stream().map(Fact::index)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        List<Integer> allMaterial = validFacts.stream().filter(index -> !validAcceptance.contains(index)).toList();
        if (binding.groupHints().isEmpty()) {
            return List.of(new Group("实现并验证验收场景", List.copyOf(validAcceptance), allMaterial, List.of()));
        }
        List<Group> result = new ArrayList<>();
        LinkedHashSet<Integer> assigned = new LinkedHashSet<>();
        for (int index = 0; index < binding.groupHints().size(); index++) {
            AcceptanceGroupHint hint = binding.groupHints().get(index);
            List<Integer> acceptanceIndexes = hint.factIndexes().stream().filter(validAcceptance::contains)
                    .filter(assigned::add).toList();
            if (acceptanceIndexes.isEmpty()) continue;
            List<Integer> materialIndexes = hint.factIndexes().stream().filter(validFacts::contains)
                    .filter(factIndex -> !validAcceptance.contains(factIndex)).toList();
            String objective = blank(hint.objective()) ? hint.title() : hint.objective();
            result.add(new Group(blank(objective) ? "实现并验证验收场景" : objective, acceptanceIndexes,
                    materialIndexes,
                    hint.dependsOnHintIndexes()));
        }
        List<Integer> remaining = validAcceptance.stream().filter(index -> !assigned.contains(index)).toList();
        if (!remaining.isEmpty()) result.add(new Group("实现并验证其余验收场景", remaining, List.of(), List.of()));
        return List.copyOf(result);
    }

    private static void ensureAcyclic(List<Group> groups) {
        for (int index = 0; index < groups.size(); index++) {
            for (Integer dependency : groups.get(index).dependsOn()) {
                if (dependency == null || dependency < 0 || dependency >= index) {
                    throw new BadRequestException("ACCEPTANCE_STAGE_DEPENDENCY_INVALID",
                            "Stage hint dependencies must reference an earlier group");
                }
            }
        }
    }

    private static Capability javaProductionGate(Group group, Catalog catalog, List<Capability> capabilities,
                                                 List<Capability> selected, ImplementationKind kind) {
        if (kind != ImplementationKind.JAVA_PRODUCTION || selected.stream().anyMatch(
                DesignerAcceptancePlanCompiler::focused)) return null;
        List<Capability> focused = capabilities.stream().filter(DesignerAcceptancePlanCompiler::focused).toList();
        StringBuilder source = new StringBuilder(group.objective());
        for (Integer factIndex : group.materialFactIndexes()) {
            Fact fact = fact(catalog, factIndex);
            source.append('\n').append(fact.title()).append('\n').append(fact.detail());
        }
        String normalized = source.toString().toLowerCase(java.util.Locale.ROOT);
        List<Capability> referenced = focused.stream().filter(capability -> capability.testTargets().stream()
                .map(target -> target.toLowerCase(java.util.Locale.ROOT)).anyMatch(normalized::contains)).toList();
        if (referenced.size() == 1) return referenced.getFirst();
        return referenced.isEmpty() && focused.size() == 1 ? focused.getFirst() : null;
    }

    private static boolean focused(Capability capability) {
        return "FOCUSED_TEST".equals(capability.kind()) && !capability.command().isEmpty()
                && !capability.testTargets().isEmpty();
    }

    private static Fact fact(Catalog catalog, int index) {
        return catalog.facts().stream().filter(fact -> fact.index() == index).findFirst()
                .orElseThrow(() -> new BadRequestException("ACCEPTANCE_FACT_INDEX_INVALID",
                        "Unknown acceptance fact index " + index));
    }

    private static LinkedHashSet<Integer> covered(List<Capability> candidates, Set<Integer> selected) {
        LinkedHashSet<Integer> covered = new LinkedHashSet<>();
        candidates.stream().filter(capability -> selected.contains(capability.index()))
                .forEach(capability -> covered.addAll(capability.coversFactIndexes()));
        return covered;
    }

    private static int newCoverage(Capability capability, Set<Integer> required, Set<Integer> covered) {
        return (int) capability.coversFactIndexes().stream().filter(required::contains)
                .filter(index -> !covered.contains(index)).count();
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static String bounded(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }

    record Result(PackageCompilationPlanEnvelope plan, List<String> normalizations,
                  SolverDiagnostics diagnostics, List<ScenarioView> scenarios,
                  MutationConservationPolicy.Evaluation mutationConservation) { }
    private record Group(String objective, List<Integer> acceptanceFactIndexes,
                         List<Integer> materialFactIndexes, List<Integer> dependsOn) { }
    private record SolveResult(List<Capability> selected, List<Integer> uncovered,
                               long exploredNodes, boolean fallbackUsed) { }

    private static final class Search {
        private final LinkedHashSet<Integer> required;
        private final List<Capability> candidates;
        private final LinkedHashSet<Integer> forced;
        private long explored;
        private SolveResult best;

        private Search(Set<Integer> required, List<Capability> candidates, Set<Integer> forced) {
            this.required = new LinkedHashSet<>(required);
            this.candidates = candidates;
            this.forced = new LinkedHashSet<>(forced);
        }

        private void run() { visit(new LinkedHashSet<>(forced), covered(candidates, forced)); }

        private void visit(LinkedHashSet<Integer> selected, LinkedHashSet<Integer> covered) {
            if (++explored > NODE_LIMIT) return;
            if (covered.containsAll(required)) {
                List<Capability> value = candidates.stream()
                        .filter(capability -> selected.contains(capability.index())).toList();
                if (best == null || score(value) < score(best.selected())) {
                    best = new SolveResult(value, List.of(), explored, false);
                }
                return;
            }
            if (best != null && selected.size() >= best.selected().size()) return;
            Integer pivot = required.stream().filter(index -> !covered.contains(index))
                    .min(Comparator.comparingLong(index -> candidates.stream()
                            .filter(capability -> capability.coversFactIndexes().contains(index)).count()))
                    .orElse(null);
            if (pivot == null) return;
            List<Capability> choices = candidates.stream()
                    .filter(capability -> capability.coversFactIndexes().contains(pivot))
                    .filter(capability -> !selected.contains(capability.index())).toList();
            for (Capability capability : choices) {
                LinkedHashSet<Integer> nextSelected = new LinkedHashSet<>(selected);
                nextSelected.add(capability.index());
                LinkedHashSet<Integer> nextCovered = new LinkedHashSet<>(covered);
                nextCovered.addAll(capability.coversFactIndexes());
                visit(nextSelected, nextCovered);
                if (explored > NODE_LIMIT) return;
            }
        }

        private static long score(List<Capability> capabilities) {
            long judges = capabilities.stream().filter(capability -> "JUDGE".equals(capability.kind())).count();
            long strength = capabilities.stream().mapToLong(Capability::strength).sum();
            return judges * 1_000_000L + capabilities.size() * 10_000L - strength;
        }
    }
}
