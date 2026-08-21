package io.opencode.loopper.service;

import static io.opencode.loopper.service.DesignerAcceptancePlanning.*;
import static io.opencode.loopper.service.DesignerSemanticContracts.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Assigns each criterion to one focused test and preserves independently required test targets. */
final class DesignerAcceptanceStageEvidenceBinder {
    Binding bind(List<Integer> factIndexes, Catalog catalog, List<Capability> selected,
                 List<String> allowedPaths, List<String> forbiddenPaths) {
        Map<Integer, Integer> localIndexes = new LinkedHashMap<>();
        List<CompactCriterion> criteria = new ArrayList<>();
        for (Integer factIndex : factIndexes) {
            Fact fact = fact(catalog, factIndex);
            localIndexes.put(factIndex, criteria.size());
            boolean review = fact.kind() == FactKind.REVIEW;
            criteria.add(new CompactCriterion(fact.acceptanceText(), List.of(fact.sourceRef()),
                    review ? fact.detail() : null,
                    review ? "该判断依赖人工语义评审，无法由确定性运行证据完整证明" : null));
        }

        Map<Integer, Capability> assigned = new LinkedHashMap<>();
        for (Integer factIndex : factIndexes) {
            selected.stream().filter(capability -> "FOCUSED_TEST".equals(capability.kind()))
                    .filter(capability -> capability.coversFactIndexes().contains(factIndex))
                    .findFirst().ifPresent(capability -> assigned.put(factIndex, capability));
        }

        List<CompactEvidence> evidence = new ArrayList<>();
        for (Capability capability : selected) {
            if (!"FOCUSED_TEST".equals(capability.kind())) continue;
            List<Integer> covers = assigned.entrySet().stream()
                    .filter(entry -> entry.getValue().index() == capability.index())
                    .map(entry -> localIndexes.get(entry.getKey())).toList();
            if (covers.isEmpty() && capability.mandatory()) {
                int criterionIndex = criteria.size();
                criteria.add(new CompactCriterion(independentCriterion(capability),
                        List.of(sourceRef(catalog, capability)), null, null));
                covers = List.of(criterionIndex);
            }
            if (!covers.isEmpty()) evidence.add(focusedEvidence(capability, covers, allowedPaths, forbiddenPaths));
        }
        evidence.add(new CompactEvidence("GIT_DIFF", List.of(), List.of(), null, null, true,
                allowedPaths, forbiddenPaths(forbiddenPaths), true, null, null, null, null, null, null,
                null, null, null, null, List.of(), List.of(), List.of()));

        List<ScenarioView> views = new ArrayList<>();
        for (Integer factIndex : factIndexes) {
            Fact fact = fact(catalog, factIndex);
            Capability capability = assigned.get(factIndex);
            CoverageMode mode = fact.kind() == FactKind.REVIEW ? CoverageMode.JUDGE
                    : capability == null ? CoverageMode.UNRESOLVED : CoverageMode.AUTOMATED;
            views.add(new ScenarioView(factIndex, fact.title(), mode,
                    capability == null ? List.of() : List.of(capability.label())));
        }
        return new Binding(List.copyOf(criteria), List.copyOf(evidence), List.copyOf(views));
    }

    private static CompactEvidence focusedEvidence(Capability capability, List<Integer> covers,
                                                    List<String> allowedPaths, List<String> forbiddenPaths) {
        return new CompactEvidence("FOCUSED_TEST", capability.command(), covers, null, null, null,
                allowedPaths, forbiddenPaths(forbiddenPaths), null, null, null, null, null, null, null,
                null, null, null, null, List.of(), List.of(), List.of());
    }

    private static String independentCriterion(Capability capability) {
        String target = capability.testTargets().isEmpty() ? capability.label()
                : String.join(", ", capability.testTargets());
        return "当执行独立聚焦测试时，" + target + " 必须单独通过。";
    }

    private static String sourceRef(Catalog catalog, Capability capability) {
        List<String> keys = capability.testTargets().stream().map(DesignerAcceptanceStageEvidenceBinder::key).toList();
        return catalog.facts().stream().filter(fact -> fact.kind() == FactKind.POLICY)
                .filter(fact -> keys.stream().anyMatch(key -> key(fact.detail()).contains(key)))
                .map(Fact::sourceRef).findFirst()
                .or(() -> catalog.facts().stream().filter(fact -> fact.kind() == FactKind.DELIVERABLE)
                        .filter(fact -> keys.stream().anyMatch(key -> key(fact.title()).contains(key)))
                        .map(Fact::sourceRef).findFirst())
                .orElseGet(() -> catalog.facts().getFirst().sourceRef());
    }

    private static List<String> forbiddenPaths(List<String> values) {
        List<String> result = new ArrayList<>(List.of(".env", ".env.*"));
        if (values != null) values.stream().filter(value -> value != null && !value.isBlank()).forEach(result::add);
        return List.copyOf(result);
    }

    private static Fact fact(Catalog catalog, int index) {
        return catalog.facts().stream().filter(fact -> fact.index() == index).findFirst().orElseThrow();
    }

    private static String key(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9\\p{IsHan}]", "");
    }

    record Binding(List<CompactCriterion> criteria, List<CompactEvidence> evidence, List<ScenarioView> views) { }
}
