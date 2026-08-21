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

    DesignerAcceptancePlanCompiler(DesignerPackagePlanCompiler packageCompiler) {
        this.packageCompiler = packageCompiler;
    }

    Result compile(DesignWorkPackageRow workPackage, String design, Catalog facts,
                   CapabilityCatalog capabilities, CompactAcceptanceBindingPlan input,
                   WorkPackageRoleService.View role, List<String> scopeIn, List<String> scopeOut,
                   List<String> deliverables, int stageLimit, boolean directSoftwareMode) {
        CompactAcceptanceBindingPlan binding = input.normalized();
        if ("DESIGN_INCOMPLETE".equals(binding.outcome())) {
            CompactPackageCompilationPlan incomplete = new CompactPackageCompilationPlan(
                    "DESIGN_INCOMPLETE", binding.summary(), List.of(), binding.handoffSummary(), binding.designGaps());
            DesignerPackagePlanCompiler.Result lowered = packageCompiler.compile(
                    workPackage, design, incomplete, stageLimit, directSoftwareMode);
            return new Result(lowered.plan(), lowered.normalizations(), emptyDiagnostics(facts, capabilities), List.of());
        }
        if (!"COMPILED".equals(binding.outcome())) {
            throw new BadRequestException("ACCEPTANCE_BINDING_OUTCOME_INVALID",
                    "Acceptance binding outcome must be COMPILED or DESIGN_INCOMPLETE");
        }
        List<Fact> acceptanceFacts = facts.facts().stream()
                .filter(fact -> fact.kind() == FactKind.SCENARIO || fact.kind() == FactKind.REVIEW).toList();
        if (acceptanceFacts.isEmpty()) {
            throw new BadRequestException("MISSING_ACCEPTANCE_INTENT", "No acceptance facts are available to bind");
        }
        List<Group> groups = groups(binding, acceptanceFacts);
        if (groups.size() > stageLimit) {
            throw new BadRequestException(directSoftwareMode ? "LARGE_TASK_MODE_REQUIRED"
                    : "COMPILER_PLAN_STAGE_COUNT_INVALID", "Acceptance groups exceed the package Stage limit");
        }
        ensureAcyclic(groups);
        List<CompactStage> stages = new ArrayList<>();
        List<ScenarioView> scenarioViews = new ArrayList<>();
        List<String> normalizations = new ArrayList<>();
        long explored = 0;
        boolean fallback = false;
        int selectedCount = 0;
        List<Integer> allUncovered = new ArrayList<>();
        List<String> allowedPaths = allowedPaths(facts, scopeIn, role);
        List<String> stageDeliverables = deliverables(facts, deliverables);
        for (Group group : groups) {
            SolveResult solved = solve(group.factIndexes(), capabilities.capabilities(), binding);
            explored += solved.exploredNodes();
            fallback |= solved.fallbackUsed();
            selectedCount += solved.selected().size();
            allUncovered.addAll(solved.uncovered());
            stages.add(stage(group, facts, capabilities, solved.selected(), allowedPaths, scopeOut,
                    stageDeliverables, role, scenarioViews));
        }
        if (!allUncovered.isEmpty()) {
            throw new BadRequestException("VERIFICATION_CAPABILITY_UNAVAILABLE",
                    "No deterministic or explicitly justified Judge capability covers facts " + allUncovered);
        }
        CompactPackageCompilationPlan compact = new CompactPackageCompilationPlan("COMPILED", binding.summary(),
                stages, binding.handoffSummary(), List.of());
        DesignerPackagePlanCompiler.Result lowered = packageCompiler.compile(
                workPackage, design, compact, stageLimit, directSoftwareMode);
        normalizations.addAll(lowered.normalizations());
        SolverDiagnostics diagnostics = new SolverDiagnostics(fallback
                ? "DETERMINISTIC_GREEDY_2OPT" : "EXACT_BRANCH_AND_BOUND", explored, fallback,
                acceptanceFacts.size(), capabilities.capabilities().size(), selectedCount,
                List.copyOf(allUncovered), normalizations);
        return new Result(lowered.plan(), List.copyOf(normalizations), diagnostics, List.copyOf(scenarioViews));
    }

    private CompactStage stage(Group group, Catalog catalog, CapabilityCatalog catalogCapabilities,
                               List<Capability> selected, List<String> allowedPaths, List<String> scopeOut,
                               List<String> deliverables, WorkPackageRoleService.View role,
                               List<ScenarioView> views) {
        Map<Integer, Integer> localIndexes = new LinkedHashMap<>();
        List<CompactCriterion> criteria = new ArrayList<>();
        for (Integer factIndex : group.factIndexes()) {
            Fact fact = fact(catalog, factIndex);
            localIndexes.put(factIndex, criteria.size());
            boolean review = fact.kind() == FactKind.REVIEW;
            criteria.add(new CompactCriterion(fact.acceptanceText(), List.of(fact.sourceRef()),
                    review ? fact.detail() : null,
                    review ? "该判断依赖人工语义评审，无法由确定性运行证据完整证明" : null));
        }
        List<CompactEvidence> evidence = new ArrayList<>();
        for (Capability capability : selected) {
            List<Integer> covers = capability.coversFactIndexes().stream().filter(localIndexes::containsKey)
                    .map(localIndexes::get).distinct().toList();
            if ("FOCUSED_TEST".equals(capability.kind()) && !covers.isEmpty()) {
                evidence.add(emptyEvidence("FOCUSED_TEST", capability.command(), covers, allowedPaths, scopeOut));
            }
        }
        evidence.add(new CompactEvidence("GIT_DIFF", List.of(), List.of(),
                null, null, true, allowedPaths, forbiddenPaths(scopeOut), true,
                null, null, null, null, null, null, null, null, null, null,
                List.of(), List.of(), List.of()));
        for (Integer factIndex : group.factIndexes()) {
            Fact fact = fact(catalog, factIndex);
            List<Capability> covering = selected.stream()
                    .filter(capability -> capability.coversFactIndexes().contains(factIndex)).toList();
            CoverageMode mode = fact.kind() == FactKind.REVIEW ? CoverageMode.JUDGE
                    : covering.isEmpty() ? CoverageMode.UNRESOLVED : CoverageMode.AUTOMATED;
            views.add(new ScenarioView(factIndex, fact.title(), mode,
                    covering.stream().map(Capability::label).toList()));
        }
        ImplementationKind kind = role.technologies().contains("java")
                ? ImplementationKind.JAVA_PRODUCTION : ImplementationKind.NON_JAVA;
        return new CompactStage(group.objective(), kind, allowedPaths, forbiddenPaths(scopeOut), deliverables,
                criteria, evidence, null);
    }

    private static CompactEvidence emptyEvidence(String kind, List<String> command, List<Integer> covers,
                                                  List<String> allowedPaths, List<String> forbiddenPaths) {
        return new CompactEvidence(kind, command, covers,
                null, null, null, allowedPaths, forbiddenPaths, null,
                null, null, null, null, null, null, null, null, null, null,
                List.of(), List.of(), List.of());
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
            if (!requiredFacts.contains(item.factIndex())) continue;
            for (Integer index : item.capabilityIndexes()) preference.putIfAbsent(index, ordinal++);
        }
        return Comparator.<Capability>comparingInt(capability -> preference.getOrDefault(capability.index(), 10_000))
                .thenComparing(Capability::deterministic, Comparator.reverseOrder())
                .thenComparing(Comparator.comparingInt(Capability::strength).reversed())
                .thenComparingInt(Capability::index);
    }

    private List<Group> groups(CompactAcceptanceBindingPlan binding, List<Fact> acceptanceFacts) {
        LinkedHashSet<Integer> valid = acceptanceFacts.stream().map(Fact::index)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        if (binding.groupHints().isEmpty()) {
            return List.of(new Group("实现并验证验收场景", List.copyOf(valid), List.of()));
        }
        List<Group> result = new ArrayList<>();
        LinkedHashSet<Integer> assigned = new LinkedHashSet<>();
        for (int index = 0; index < binding.groupHints().size(); index++) {
            AcceptanceGroupHint hint = binding.groupHints().get(index);
            List<Integer> indexes = hint.factIndexes().stream().filter(valid::contains).filter(assigned::add).toList();
            if (indexes.isEmpty()) continue;
            String objective = blank(hint.objective()) ? hint.title() : hint.objective();
            result.add(new Group(blank(objective) ? "实现并验证验收场景" : objective, indexes,
                    hint.dependsOnHintIndexes()));
        }
        List<Integer> remaining = valid.stream().filter(index -> !assigned.contains(index)).toList();
        if (!remaining.isEmpty()) result.add(new Group("实现并验证其余验收场景", remaining, List.of()));
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

    private static List<String> allowedPaths(Catalog facts, List<String> scopeIn, WorkPackageRoleService.View role) {
        LinkedHashSet<String> paths = new LinkedHashSet<>();
        facts.facts().stream().filter(fact -> fact.kind() == FactKind.DELIVERABLE || fact.kind() == FactKind.SCOPE)
                .map(Fact::title).filter(DesignerAcceptancePlanCompiler::pathLike).forEach(paths::add);
        if (scopeIn != null) scopeIn.stream().filter(DesignerAcceptancePlanCompiler::pathLike).forEach(paths::add);
        if (paths.isEmpty() && role.technologies().contains("java")) {
            paths.add("src/main/java/**"); paths.add("src/test/java/**");
        } else if (paths.isEmpty() && role.technologies().contains("python")) {
            paths.add("**/*.py");
        } else if (paths.isEmpty()) paths.add("src/**");
        return List.copyOf(paths);
    }

    private static List<String> deliverables(Catalog facts, List<String> frozen) {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        if (frozen != null) frozen.stream().filter(value -> !blank(value)).forEach(values::add);
        facts.facts().stream().filter(fact -> fact.kind() == FactKind.DELIVERABLE)
                .map(Fact::title).filter(value -> !blank(value)).forEach(values::add);
        if (values.isEmpty()) values.add("实现与聚焦验收测试");
        return List.copyOf(values);
    }

    private static List<String> forbiddenPaths(List<String> scopeOut) {
        LinkedHashSet<String> values = new LinkedHashSet<>(List.of(".env", ".env.*"));
        if (scopeOut != null) scopeOut.stream().filter(DesignerAcceptancePlanCompiler::pathLike).forEach(values::add);
        return List.copyOf(values);
    }

    private static Fact fact(Catalog catalog, int index) {
        return catalog.facts().stream().filter(fact -> fact.index() == index).findFirst()
                .orElseThrow(() -> new BadRequestException("ACCEPTANCE_FACT_INDEX_INVALID",
                        "Unknown acceptance fact index " + index));
    }

    private static SolverDiagnostics emptyDiagnostics(Catalog facts, CapabilityCatalog capabilities) {
        return new SolverDiagnostics("NOT_RUN", 0, false, facts.facts().size(),
                capabilities.capabilities().size(), 0, List.of(), List.of());
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

    private static boolean pathLike(String value) {
        return !blank(value) && (value.contains("/") || value.contains("\\") || value.contains("*")
                || value.matches(".*\\.[A-Za-z0-9]{1,10}$"));
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }

    record Result(PackageCompilationPlanEnvelope plan, List<String> normalizations,
                  SolverDiagnostics diagnostics, List<ScenarioView> scenarios) { }
    private record Group(String objective, List<Integer> factIndexes, List<Integer> dependsOn) { }
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
