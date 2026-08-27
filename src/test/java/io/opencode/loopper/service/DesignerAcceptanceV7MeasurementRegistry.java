package io.opencode.loopper.service;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/** Test-only sink for bounded values observed inside exact production guards. */
public final class DesignerAcceptanceV7MeasurementRegistry {
    private static final Map<String, Set<String>> ALLOWED_METRICS = Map.of(
            "same-input-production-pipeline", Set.of(
                    "v6Compiled", "v7Compiled", "v6Executable", "v7Executable",
                    "v6CompilerCalls", "v7CompilerCalls", "v6Redesigns", "v7Redesigns",
                    "v7PathEscapes", "v7DangerousAutoAuthorizations", "v6JudgeOnly", "v7JudgeOnly",
                    "v6FocusedRequired", "v6FocusedCovered", "v7FocusedRequired", "v7FocusedCovered"),
            "server-direct-path-conservation", Set.of(
                    "v7CompilerCalls", "v7Redesigns", "v7MutationTotal", "v7MutationResolved",
                    "v7MutationUnresolved", "v7Acceptance", "v7JudgeOnly", "v7FocusedCovered"),
            "ambiguous-stage-safety", Set.of(
                    "v7CompilerCalls", "v7Redesigns", "v7MutationTotal", "v7MutationResolved",
                    "v7MutationUnresolved", "v7HardGaps", "v7BlockedHardGaps"),
            "large-package-v6-v7-cost", Set.of(
                    "v6CompilerCalls", "v7CompilerCalls", "v6Redesigns", "v7Redesigns"),
            "capability-resolution", Set.of(
                    "v7UniqueOptimumRequiredCompilerCalls", "v7DeterministicWinnerRequiredCompilerCalls",
                    "v7TrueTieRequiredCompilerCalls", "v7TrueTieOptimalSolutions"),
            "closed-choice-workflow-calls", Set.of("actualCompilerCalls", "actualCompilerSessions"),
            "external-system-write-safety", Set.of("blockedRequests", "unsafeRequestsAllowed"));
    private static final Map<String, Set<String>> ALLOWED_FLAGS = Map.of(
            "same-input-production-pipeline", Set.of("PRODUCTION_PIPELINE", "SAME_FROZEN_INPUT"),
            "server-direct-path-conservation", Set.of("SERVER_COMPILED", "CONSERVED"),
            "ambiguous-stage-safety", Set.of("DESIGN_INCOMPLETE", "REQUIRED_MUTATION_PATH_UNASSIGNED"),
            "large-package-v6-v7-cost", Set.of("V6_FROZEN_COMPATIBILITY", "V7_SERVER_DIRECT"),
            "capability-resolution", Set.of("TRUE_TIE_MEASURED", "UNIQUE_OPTIMUM_MEASURED"),
            "closed-choice-workflow-calls", Set.of("CLOSED_CHOICE_V7", "COMPILER_BINDING_NO_TOOLS"),
            "external-system-write-safety", Set.of("EXTERNAL_WRITE_BLOCKED"));
    private static final Map<String, MutableEvidence> EVIDENCE = new LinkedHashMap<>();

    private DesignerAcceptanceV7MeasurementRegistry() { }

    public static synchronized void clear() {
        EVIDENCE.clear();
    }

    public static synchronized void record(String evidenceId, Map<String, Integer> metrics, Set<String> flags) {
        Set<String> allowedMetrics = ALLOWED_METRICS.get(evidenceId);
        Set<String> allowedFlags = ALLOWED_FLAGS.get(evidenceId);
        if (allowedMetrics == null || allowedFlags == null) {
            throw new IllegalArgumentException("Measured evidence id is outside the closed contract");
        }
        Map<String, Integer> boundedMetrics = metrics == null ? Map.of() : metrics;
        Set<String> boundedFlags = flags == null ? Set.of() : flags;
        if (!allowedMetrics.containsAll(boundedMetrics.keySet())) {
            throw new IllegalArgumentException("Measured metric name is outside the closed contract");
        }
        if (!allowedFlags.containsAll(boundedFlags)) {
            throw new IllegalArgumentException("Measured flag is outside the closed contract");
        }
        MutableEvidence target = EVIDENCE.computeIfAbsent(evidenceId, ignored -> new MutableEvidence());
        boundedMetrics.forEach((name, value) -> {
            if (name == null || name.isBlank() || value == null || value < 0) {
                throw new IllegalArgumentException("Measured metrics require a name and non-negative value");
            }
            Integer previous = target.metrics.putIfAbsent(name, value);
            if (previous != null && !previous.equals(value)) {
                throw new IllegalStateException("Conflicting measured metric " + evidenceId + ":" + name);
            }
        });
        target.flags.addAll(boundedFlags);
    }

    public static synchronized List<Evidence> snapshot() {
        return EVIDENCE.entrySet().stream().map(entry -> new Evidence(entry.getKey(),
                entry.getValue().metrics, entry.getValue().flags))
                .sorted(java.util.Comparator.comparing(Evidence::evidenceId)).toList();
    }

    public record Evidence(String evidenceId, Map<String, Integer> metrics, Set<String> flags) {
        public Evidence {
            metrics = metrics == null ? Map.of()
                    : java.util.Collections.unmodifiableMap(new TreeMap<>(metrics));
            flags = flags == null ? Set.of()
                    : java.util.Collections.unmodifiableSet(new TreeSet<>(flags));
        }
    }

    private static final class MutableEvidence {
        private final Map<String, Integer> metrics = new LinkedHashMap<>();
        private final Set<String> flags = new LinkedHashSet<>();
    }
}
