package io.opencode.loopper.service;

import static io.opencode.loopper.service.DesignerAcceptancePlanning.*;
import static io.opencode.loopper.service.DesignerSemanticContracts.*;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Selects a deterministic minimum capability cover for one frozen acceptance-fact group. */
final class DesignerAcceptanceCapabilitySolver {
    private static final long NODE_LIMIT = 100_000;

    Result solve(List<Integer> requiredFacts, List<Capability> all, CompactAcceptanceBindingPlan binding) {
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

    private static Result greedy(Set<Integer> required, List<Capability> candidates,
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
        return new Result(result, uncovered, explored, true);
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

    record Result(List<Capability> selected, List<Integer> uncovered,
                  long exploredNodes, boolean fallbackUsed) { }

    private static final class Search {
        private final LinkedHashSet<Integer> required;
        private final List<Capability> candidates;
        private final LinkedHashSet<Integer> forced;
        private long explored;
        private Result best;

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
                    best = new Result(value, List.of(), explored, false);
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
