package io.opencode.loopper.service;

import static io.opencode.loopper.service.DesignerAcceptancePlanning.*;
import static io.opencode.loopper.service.DesignerSemanticContracts.*;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Selects a deterministic minimum capability cover for one frozen acceptance-fact group. */
final class DesignerAcceptanceCapabilitySolver {
    private static final long DEFAULT_NODE_LIMIT = 100_000;
    private final long nodeLimit;

    DesignerAcceptanceCapabilitySolver() {
        this(DEFAULT_NODE_LIMIT);
    }

    DesignerAcceptanceCapabilitySolver(long nodeLimit) {
        this.nodeLimit = nodeLimit;
    }

    Result solve(List<Integer> requiredFacts, List<Capability> all, CompactAcceptanceBindingPlan binding) {
        return solve(requiredFacts, all, binding, false);
    }

    Result solveV7(List<Integer> requiredFacts, List<Capability> all, CompactAcceptanceBindingPlan binding) {
        return solve(requiredFacts, all, binding, true);
    }

    private Result solve(List<Integer> requiredFacts, List<Capability> all,
                         CompactAcceptanceBindingPlan binding, boolean currentV7) {
        LinkedHashSet<Integer> required = new LinkedHashSet<>(requiredFacts);
        List<Capability> candidates = all.stream()
                .filter(capability -> capability.coversFactIndexes().stream().anyMatch(required::contains))
                .sorted(capabilityComparator(binding, required)).toList();
        LinkedHashSet<Integer> forcedIndexes = new LinkedHashSet<>();
        for (Capability candidate : candidates) if (candidate.mandatory()) forcedIndexes.add(candidate.index());
        Search search = new Search(required, candidates, forcedIndexes, currentV7, nodeLimit);
        search.run();
        if (search.best != null) return search.result();
        return greedy(required, candidates, forcedIndexes, search.explored, !search.limitExceeded);
    }

    private static Result greedy(Set<Integer> required, List<Capability> candidates,
                                 Set<Integer> forcedIndexes, long explored, boolean exhaustive) {
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
        return new Result(result, uncovered, explored, true, exhaustive, 0,
                ambiguousFacts(required, candidates), Map.of(), List.of());
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
                  long exploredNodes, boolean fallbackUsed, boolean exhaustive,
                  int optimalSolutionCount, List<Integer> tiedFactIndexes,
                  Map<Integer, List<Integer>> tiedCapabilityIndexesByFact,
                  List<List<Integer>> optimalTieChoiceSets) {
        Result {
            selected = selected == null ? List.of() : List.copyOf(selected);
            uncovered = uncovered == null ? List.of() : List.copyOf(uncovered);
            tiedFactIndexes = tiedFactIndexes == null ? List.of() : List.copyOf(tiedFactIndexes);
            LinkedHashMap<Integer, List<Integer>> copied = new LinkedHashMap<>();
            if (tiedCapabilityIndexesByFact != null) {
                tiedCapabilityIndexesByFact.forEach((factIndex, capabilityIndexes) ->
                        copied.put(factIndex, capabilityIndexes == null ? List.of() : List.copyOf(capabilityIndexes)));
            }
            tiedCapabilityIndexesByFact = java.util.Collections.unmodifiableMap(copied);
            optimalTieChoiceSets = optimalTieChoiceSets == null ? List.of()
                    : optimalTieChoiceSets.stream().map(List::copyOf).toList();
        }

        boolean uniqueOptimum() {
            return exhaustive && uncovered.isEmpty() && optimalSolutionCount == 1;
        }
    }

    private static final class Search {
        private final LinkedHashSet<Integer> required;
        private final List<Capability> candidates;
        private final LinkedHashSet<Integer> forced;
        private final boolean currentV7;
        private final long nodeLimit;
        private long explored;
        private boolean limitExceeded;
        private List<Capability> best;
        private BusinessScore bestScore;
        private final LinkedHashMap<String, List<Capability>> optimal = new LinkedHashMap<>();

        private Search(Set<Integer> required, List<Capability> candidates, Set<Integer> forced,
                       boolean currentV7, long nodeLimit) {
            this.required = new LinkedHashSet<>(required);
            this.candidates = candidates;
            this.forced = new LinkedHashSet<>(forced);
            this.currentV7 = currentV7;
            this.nodeLimit = nodeLimit;
        }

        private void run() { visit(new LinkedHashSet<>(forced), covered(candidates, forced)); }

        private void visit(LinkedHashSet<Integer> selected, LinkedHashSet<Integer> covered) {
            if (++explored > nodeLimit) {
                limitExceeded = true;
                return;
            }
            if (covered.containsAll(required)) {
                List<Capability> value = candidates.stream()
                        .filter(capability -> selected.contains(capability.index())).toList();
                BusinessScore candidateScore = score(value, currentV7);
                if (best == null || candidateScore.compareTo(bestScore) < 0) {
                    best = value;
                    bestScore = candidateScore;
                    optimal.clear();
                    optimal.put(signature(value), value);
                } else if (candidateScore.equals(bestScore)) {
                    optimal.putIfAbsent(signature(value), value);
                }
                return;
            }
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
                if (limitExceeded) return;
            }
        }

        private Result result() {
            Map<Integer, List<Integer>> tiedCapabilities = tiedCapabilities(required, optimal.values());
            return new Result(best, List.of(), explored, false, !limitExceeded, optimal.size(),
                    List.copyOf(tiedCapabilities.keySet()), tiedCapabilities,
                    optimalTieChoiceSets(optimal.values(), tiedCapabilities));
        }

        private static BusinessScore score(List<Capability> capabilities, boolean currentV7) {
            int judges = (int) capabilities.stream().filter(capability -> "JUDGE".equals(capability.kind())).count();
            int nondeterministic = currentV7 ? (int) capabilities.stream()
                    .filter(capability -> !capability.deterministic()).count() : 0;
            long strength = capabilities.stream().mapToLong(Capability::strength).sum();
            return new BusinessScore(judges, nondeterministic, strength, capabilities.size());
        }

        private static String signature(List<Capability> capabilities) {
            return capabilities.stream().map(Capability::index).sorted().map(String::valueOf)
                    .collect(java.util.stream.Collectors.joining(","));
        }
    }

    private record BusinessScore(int judges, int nondeterministic, long strength, int capabilityCount)
            implements Comparable<BusinessScore> {
        @Override
        public int compareTo(BusinessScore other) {
            int result = Integer.compare(judges, other.judges);
            if (result != 0) return result;
            result = Integer.compare(nondeterministic, other.nondeterministic);
            if (result != 0) return result;
            result = Integer.compare(capabilityCount, other.capabilityCount);
            return result != 0 ? result : Long.compare(other.strength, strength);
        }
    }

    private static Map<Integer, List<Integer>> tiedCapabilities(
            Set<Integer> required, java.util.Collection<List<Capability>> solutions) {
        LinkedHashMap<Integer, List<Integer>> tied = new LinkedHashMap<>();
        int solutionCount = solutions.size();
        for (Integer factIndex : required) {
            LinkedHashMap<Integer, Integer> selectedCounts = new LinkedHashMap<>();
            for (List<Capability> solution : solutions) {
                solution.stream().filter(capability -> capability.coversFactIndexes().contains(factIndex))
                        .map(Capability::index).forEach(index -> selectedCounts.merge(index, 1, Integer::sum));
            }
            List<Integer> discriminating = selectedCounts.entrySet().stream()
                    .filter(entry -> entry.getValue() < solutionCount).map(Map.Entry::getKey).toList();
            if (!discriminating.isEmpty()) tied.put(factIndex, discriminating);
        }
        return java.util.Collections.unmodifiableMap(tied);
    }

    private static List<List<Integer>> optimalTieChoiceSets(
            java.util.Collection<List<Capability>> solutions,
            Map<Integer, List<Integer>> tiedCapabilitiesByFact) {
        LinkedHashSet<Integer> discriminating = tiedCapabilitiesByFact.values().stream()
                .flatMap(List::stream).collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        LinkedHashMap<String, List<Integer>> choices = new LinkedHashMap<>();
        for (List<Capability> solution : solutions) {
            List<Integer> indexes = solution.stream().map(Capability::index).filter(discriminating::contains)
                    .sorted().toList();
            choices.putIfAbsent(indexes.toString(), indexes);
        }
        return List.copyOf(choices.values());
    }

    private static List<Integer> ambiguousFacts(Set<Integer> required, List<Capability> candidates) {
        return required.stream().filter(factIndex -> candidates.stream()
                .filter(capability -> capability.coversFactIndexes().contains(factIndex)).count() > 1).toList();
    }
}
