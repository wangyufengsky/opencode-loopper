package io.opencode.loopper.service;

import static io.opencode.loopper.service.DesignerAcceptancePlanning.*;
import static io.opencode.loopper.service.DesignerSemanticContracts.*;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Resolves the v6 Designer stage table without allowing fuzzy or substring binding. */
final class DesignerAcceptanceFastPathResolver {
    private static final int MIN_STAGES = 1;
    private static final int MAX_STAGES = 6;

    Resolution resolve(Catalog catalog, CapabilityCatalog capabilities) {
        List<StageHint> stages = catalog.stageHints();
        if (stages.size() > MAX_STAGES) {
            return new Resolution(Outcome.DESIGN_INCOMPLETE, List.of(), List.of(), List.of(),
                    List.of("ACCEPTANCE_STAGE_COUNT_INVALID"),
                    List.of(new DesignGap(DesignGapCode.LARGE_TASK_MODE_REQUIRED,
                            "阶段表超过普通单包允许的 1–6 个阶段，当前为 " + stages.size())));
        }
        if (stages.size() < MIN_STAGES) {
            return incomplete("ACCEPTANCE_STAGE_COUNT_INVALID",
                    "阶段表必须包含 1–6 个阶段，当前为 " + stages.size());
        }
        Map<String, Integer> stageSymbols = new LinkedHashMap<>();
        for (int index = 0; index < stages.size(); index++) {
            String key = symbol(stages.get(index).title());
            if (key.isBlank()) return incomplete("ACCEPTANCE_STAGE_TITLE_MISSING", "第 " + (index + 1) + " 个阶段缺少名称");
            if (stageSymbols.putIfAbsent(key, index) != null) {
                return incomplete("ACCEPTANCE_STAGE_TITLE_DUPLICATE", "阶段名称重复：" + stages.get(index).title());
            }
        }

        Map<String, List<Fact>> factSymbols = new LinkedHashMap<>();
        List<Fact> acceptanceFacts = catalog.facts().stream().filter(DesignerAcceptanceFastPathResolver::acceptance)
                .toList();
        catalog.facts().stream().filter(DesignerAcceptanceFastPathResolver::referable)
                .forEach(fact -> factSymbols.computeIfAbsent(symbol(fact.title()), ignored -> new ArrayList<>()).add(fact));
        List<LinkedHashSet<Integer>> assignments = new ArrayList<>();
        List<List<Integer>> dependencies = new ArrayList<>();
        List<String> reasons = new ArrayList<>();
        LinkedHashSet<Integer> unresolvedFacts = new LinkedHashSet<>();
        LinkedHashSet<Integer> ambiguousCapabilities = new LinkedHashSet<>();
        Map<Integer, Integer> acceptanceOwners = new LinkedHashMap<>();

        for (int stageIndex = 0; stageIndex < stages.size(); stageIndex++) {
            StageHint stage = stages.get(stageIndex);
            LinkedHashSet<Integer> stageFacts = new LinkedHashSet<>();
            for (String reference : stage.includedReferences()) {
                List<Fact> matches = factSymbols.getOrDefault(symbol(reference), List.of());
                if (matches.size() != 1) {
                    reasons.add((matches.isEmpty() ? "UNKNOWN_FACT_REFERENCE:" : "AMBIGUOUS_FACT_REFERENCE:")
                            + reference);
                    continue;
                }
                Fact fact = matches.getFirst();
                if (acceptance(fact)) {
                    Integer previous = acceptanceOwners.putIfAbsent(fact.index(), stageIndex);
                    if (previous != null && previous != stageIndex) {
                        return incomplete("ACCEPTANCE_FACT_ASSIGNED_MORE_THAN_ONCE",
                                "验收事实“" + fact.title() + "”同时出现在多个阶段");
                    }
                }
                stageFacts.add(fact.index());
            }
            assignments.add(stageFacts);

            List<Integer> stageDependencies = new ArrayList<>();
            for (String reference : stage.dependencyReferences()) {
                Integer dependency = stageSymbols.get(symbol(reference));
                if (dependency == null) {
                    return incomplete("ACCEPTANCE_STAGE_DEPENDENCY_UNKNOWN", "未知前置阶段：" + reference);
                }
                if (dependency >= stageIndex) {
                    return incomplete("ACCEPTANCE_STAGE_DEPENDENCY_NOT_PRIOR",
                            "前置阶段必须引用更早阶段：" + reference);
                }
                if (!stageDependencies.contains(dependency)) stageDependencies.add(dependency);
            }
            dependencies.add(List.copyOf(stageDependencies));
        }

        for (Fact fact : acceptanceFacts) {
            if (!acceptanceOwners.containsKey(fact.index())) unresolvedFacts.add(fact.index());
            List<Capability> covering = capabilities.capabilities().stream()
                    .filter(capability -> capability.coversFactIndexes().contains(fact.index())).toList();
            if (fact.kind() == FactKind.SCENARIO && covering.isEmpty()) {
                return incomplete("VERIFICATION_CAPABILITY_UNAVAILABLE",
                        "验收事实“" + fact.title() + "”没有可执行验证能力");
            }
            if (covering.size() > 1) {
                ambiguousCapabilities.add(fact.index());
                reasons.add("AMBIGUOUS_CAPABILITY:" + fact.index());
            }
        }
        if (!unresolvedFacts.isEmpty()) reasons.add("UNRESOLVED_FACTS:" + unresolvedFacts);

        if (unresolvedFacts.isEmpty() && reasons.stream().anyMatch(reason ->
                reason.startsWith("UNKNOWN_FACT_REFERENCE:")
                        || reason.startsWith("AMBIGUOUS_FACT_REFERENCE:"))) {
            return incomplete("ACCEPTANCE_FACT_REFERENCE_INVALID",
                    "阶段表包含无法绑定且不对应任何待分配验收事实的引用");
        }

        for (int index = 0; index < stages.size(); index++) {
            boolean containsAcceptance = assignments.get(index).stream().anyMatch(factIndex ->
                    acceptanceFacts.stream().anyMatch(fact -> fact.index() == factIndex));
            if (!containsAcceptance && unresolvedFacts.isEmpty()) {
                return incomplete("ACCEPTANCE_STAGE_WITHOUT_FACT", "阶段“" + stages.get(index).title() + "”没有验收事实");
            }
        }

        List<AcceptanceGroupHint> groups = new ArrayList<>();
        for (int index = 0; index < stages.size(); index++) {
            StageHint stage = stages.get(index);
            groups.add(new AcceptanceGroupHint(stage.title(), blank(stage.objective()) ? stage.title() : stage.objective(),
                    List.copyOf(assignments.get(index)), dependencies.get(index)));
        }
        Outcome outcome = unresolvedFacts.isEmpty() && ambiguousCapabilities.isEmpty()
                ? Outcome.RESOLVED : Outcome.NEEDS_COMPILER;
        return new Resolution(outcome, List.copyOf(groups), List.copyOf(unresolvedFacts),
                List.copyOf(ambiguousCapabilities), List.copyOf(reasons), List.of());
    }

    CompactAcceptanceBindingPlan merge(Resolution resolution, CompactAcceptanceDisambiguationPlan input,
                                       Catalog catalog, CapabilityCatalog capabilities) {
        if (resolution.outcome() == Outcome.DESIGN_INCOMPLETE) {
            throw new BadRequestException("AMBIGUOUS_ACCEPTANCE_INTENT", "设计骨架不可消歧");
        }
        CompactAcceptanceDisambiguationPlan plan = input.normalized();
        Set<Integer> unresolved = new LinkedHashSet<>(resolution.unresolvedFactIndexes());
        Set<Integer> ambiguousCapabilities = new LinkedHashSet<>(resolution.ambiguousCapabilityFactIndexes());
        Map<Integer, Integer> assignments = new LinkedHashMap<>();
        for (AcceptanceFactAssignment item : plan.factAssignments()) {
            if (item == null || item.factIndex() == null || item.stageIndex() == null
                    || !unresolved.contains(item.factIndex())
                    || item.stageIndex() < 0 || item.stageIndex() >= resolution.groupHints().size()
                    || assignments.putIfAbsent(item.factIndex(), item.stageIndex()) != null) {
                throw invalid("Compiler returned an illegal or duplicate unresolved fact assignment");
            }
        }
        if (!assignments.keySet().equals(unresolved)) {
            throw invalid("Compiler must assign every and only unresolved acceptance fact");
        }
        Map<Integer, AcceptanceCapabilityPreference> preferences = new LinkedHashMap<>();
        for (AcceptanceCapabilityPreference preference : plan.capabilityPreferences()) {
            if (preference == null) throw invalid("Compiler returned an empty capability preference");
            Integer factIndex = preference.factIndex();
            if (factIndex == null || !ambiguousCapabilities.contains(factIndex)
                    || preferences.putIfAbsent(factIndex, preference) != null) {
                throw invalid("Compiler returned an illegal or duplicate capability preference");
            }
            Set<Integer> allowed = capabilities.capabilities().stream()
                    .filter(capability -> capability.coversFactIndexes().contains(factIndex))
                    .map(Capability::index).collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
            if (preference.capabilityIndexes().isEmpty()
                    || preference.capabilityIndexes().stream().anyMatch(index -> !allowed.contains(index))) {
                throw invalid("Compiler capability preference is outside the frozen candidate set");
            }
        }
        if (!preferences.keySet().equals(ambiguousCapabilities)) {
            throw invalid("Compiler must choose preferences for every ambiguous capability binding");
        }

        List<AcceptanceGroupHint> groups = new ArrayList<>();
        for (int index = 0; index < resolution.groupHints().size(); index++) {
            AcceptanceGroupHint locked = resolution.groupHints().get(index);
            LinkedHashSet<Integer> facts = new LinkedHashSet<>(locked.factIndexes());
            int stageIndex = index;
            assignments.forEach((factIndex, assignedStage) -> {
                if (assignedStage == stageIndex) facts.add(factIndex);
            });
            if (facts.stream().noneMatch(factIndex -> catalog.facts().stream()
                    .anyMatch(fact -> fact.index() == factIndex && acceptance(fact)))) {
                throw invalid("Compiler left a stage without an acceptance fact");
            }
            groups.add(new AcceptanceGroupHint(locked.title(), locked.objective(), List.copyOf(facts),
                    locked.dependsOnHintIndexes()));
        }
        return new CompactAcceptanceBindingPlan(plan.summary(), List.copyOf(groups),
                List.copyOf(preferences.values()), plan.handoffSummary()).normalized();
    }

    private static Resolution incomplete(String code, String detail) {
        return new Resolution(Outcome.DESIGN_INCOMPLETE, List.of(), List.of(), List.of(), List.of(code),
                List.of(new DesignGap(DesignGapCode.AMBIGUOUS_ACCEPTANCE_INTENT, detail)));
    }

    private static BadRequestException invalid(String detail) {
        return new BadRequestException("AMBIGUOUS_ACCEPTANCE_INTENT", detail);
    }

    static String symbol(String value) {
        if (value == null) return "";
        return Normalizer.normalize(value, Normalizer.Form.NFKC).trim().replaceAll("\\s+", " ")
                .toLowerCase(Locale.ROOT);
    }

    private static boolean acceptance(Fact fact) {
        return fact.kind() == FactKind.SCENARIO || fact.kind() == FactKind.REVIEW;
    }

    private static boolean referable(Fact fact) {
        return acceptance(fact) || fact.kind() == FactKind.DELIVERABLE;
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }

    enum Outcome { RESOLVED, NEEDS_COMPILER, DESIGN_INCOMPLETE }

    record Resolution(Outcome outcome, List<AcceptanceGroupHint> groupHints,
                      List<Integer> unresolvedFactIndexes, List<Integer> ambiguousCapabilityFactIndexes,
                      List<String> routingReasons, List<DesignGap> designGaps) {
        Resolution {
            groupHints = groupHints == null ? List.of() : List.copyOf(groupHints);
            unresolvedFactIndexes = unresolvedFactIndexes == null ? List.of() : List.copyOf(unresolvedFactIndexes);
            ambiguousCapabilityFactIndexes = ambiguousCapabilityFactIndexes == null
                    ? List.of() : List.copyOf(ambiguousCapabilityFactIndexes);
            routingReasons = routingReasons == null ? List.of() : List.copyOf(routingReasons);
            designGaps = designGaps == null ? List.of() : List.copyOf(designGaps);
        }
    }
}
