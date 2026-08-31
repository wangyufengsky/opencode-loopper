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
    private static final String CANDIDATE_UNIQUE_OPTIMUM = "acceptance-candidate-unique-optimum-usage";
    private static final String CANDIDATE_TRUE_TIE = "acceptance-candidate-true-tie-usage";
    private static final String CANDIDATE_NON_ENUMERABLE = "acceptance-candidate-non-enumerable-usage";
    private static final String CANDIDATE_PATH_SAFETY = "acceptance-candidate-path-safety-usage";
    private static final Set<String> CANDIDATE_EVIDENCE_IDS = Set.of(
            CANDIDATE_UNIQUE_OPTIMUM, CANDIDATE_TRUE_TIE,
            CANDIDATE_NON_ENUMERABLE, CANDIDATE_PATH_SAFETY);
    private static final Set<String> CANDIDATE_USAGE_METRICS = Set.of(
            "modelCalls", "candidateSessions", "candidateSubmissions");
    private static final Map<String, Set<String>> ALLOWED_METRICS = Map.ofEntries(
            Map.entry("same-input-production-pipeline", Set.of(
                    "v6Compiled", "v7Compiled", "v6Executable", "v7Executable",
                    "v6CompilerCalls", "v7CompilerCalls", "v6Redesigns", "v7Redesigns",
                    "v7PathEscapes", "v7DangerousAutoAuthorizations", "v6JudgeOnly", "v7JudgeOnly",
                    "v6FocusedRequired", "v6FocusedCovered", "v7FocusedRequired", "v7FocusedCovered")),
            Map.entry("server-direct-path-conservation", Set.of(
                    "v7CompilerCalls", "v7Redesigns", "v7MutationTotal", "v7MutationResolved",
                    "v7MutationUnresolved", "v7Acceptance", "v7JudgeOnly", "v7FocusedCovered")),
            Map.entry("ambiguous-stage-safety", Set.of(
                    "v7CompilerCalls", "v7Redesigns", "v7MutationTotal", "v7MutationResolved",
                    "v7MutationUnresolved", "v7HardGaps", "v7BlockedHardGaps")),
            Map.entry("large-package-v6-v7-cost", Set.of(
                    "v6CompilerCalls", "v7CompilerCalls", "v6Redesigns", "v7Redesigns")),
            Map.entry("capability-resolution", Set.of(
                    "v7UniqueOptimumRequiredCompilerCalls", "v7DeterministicWinnerRequiredCompilerCalls",
                    "v7TrueTieRequiredCompilerCalls", "v7TrueTieOptimalSolutions")),
            Map.entry("closed-choice-workflow-calls", Set.of("actualCompilerCalls", "actualCompilerSessions")),
            Map.entry("external-system-write-safety", Set.of("blockedRequests", "unsafeRequestsAllowed")),
            Map.entry(CANDIDATE_UNIQUE_OPTIMUM, CANDIDATE_USAGE_METRICS),
            Map.entry(CANDIDATE_TRUE_TIE, CANDIDATE_USAGE_METRICS),
            Map.entry(CANDIDATE_NON_ENUMERABLE, CANDIDATE_USAGE_METRICS),
            Map.entry(CANDIDATE_PATH_SAFETY, CANDIDATE_USAGE_METRICS));
    private static final Map<String, Set<String>> ALLOWED_FLAGS = Map.ofEntries(
            Map.entry("same-input-production-pipeline", Set.of("PRODUCTION_PIPELINE", "SAME_FROZEN_INPUT")),
            Map.entry("server-direct-path-conservation", Set.of("SERVER_COMPILED", "CONSERVED")),
            Map.entry("ambiguous-stage-safety", Set.of("DESIGN_INCOMPLETE", "REQUIRED_MUTATION_PATH_UNASSIGNED")),
            Map.entry("large-package-v6-v7-cost", Set.of("V6_FROZEN_COMPATIBILITY", "V7_SERVER_DIRECT")),
            Map.entry("capability-resolution", Set.of("TRUE_TIE_MEASURED", "UNIQUE_OPTIMUM_MEASURED")),
            Map.entry("closed-choice-workflow-calls", Set.of("CLOSED_CHOICE_V7", "COMPILER_BINDING_NO_TOOLS")),
            Map.entry("external-system-write-safety", Set.of("EXTERNAL_WRITE_BLOCKED")),
            Map.entry(CANDIDATE_UNIQUE_OPTIMUM, Set.of("UNIQUE_OPTIMUM")),
            Map.entry(CANDIDATE_TRUE_TIE, Set.of("TRUE_TIE")),
            Map.entry(CANDIDATE_NON_ENUMERABLE, Set.of("NON_ENUMERABLE")),
            Map.entry(CANDIDATE_PATH_SAFETY, Set.of("PATH_SAFETY")));
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
        if (CANDIDATE_EVIDENCE_IDS.contains(evidenceId)
                && (!boundedMetrics.keySet().equals(CANDIDATE_USAGE_METRICS)
                || !boundedFlags.equals(allowedFlags))) {
            throw new IllegalArgumentException(
                    "Candidate usage must record all runtime axes from the same observation");
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

    public static synchronized CandidateQualification candidateQualification() {
        Set<String> missing = new TreeSet<>(CANDIDATE_EVIDENCE_IDS);
        missing.removeAll(EVIDENCE.keySet());
        boolean complete = missing.isEmpty();
        boolean passed = complete
                && exactUsage(CANDIDATE_UNIQUE_OPTIMUM, 0, 0, 0, 0)
                && exactUsage(CANDIDATE_TRUE_TIE, 1, 1, 1, 2)
                && exactUsage(CANDIDATE_NON_ENUMERABLE, 0, 0, 0, 0)
                && exactUsage(CANDIDATE_PATH_SAFETY, 0, 0, 0, 0);
        return new CandidateQualification(complete, passed, List.copyOf(missing));
    }

    private static boolean exactUsage(String evidenceId, int modelCalls, int candidateSessions,
                                      int minimumSubmissions, int maximumSubmissions) {
        MutableEvidence evidence = EVIDENCE.get(evidenceId);
        if (evidence == null) {
            return false;
        }
        int submissions = evidence.metrics.getOrDefault("candidateSubmissions", -1);
        return evidence.metrics.getOrDefault("modelCalls", -1) == modelCalls
                && evidence.metrics.getOrDefault("candidateSessions", -1) == candidateSessions
                && submissions >= minimumSubmissions && submissions <= maximumSubmissions;
    }

    public record Evidence(String evidenceId, Map<String, Integer> metrics, Set<String> flags) {
        public Evidence {
            metrics = metrics == null ? Map.of()
                    : java.util.Collections.unmodifiableMap(new TreeMap<>(metrics));
            flags = flags == null ? Set.of()
                    : java.util.Collections.unmodifiableSet(new TreeSet<>(flags));
        }
    }

    public record CandidateQualification(boolean complete, boolean passed,
                                         List<String> missingEvidenceIds) {
        public CandidateQualification {
            missingEvidenceIds = missingEvidenceIds == null ? List.of() : List.copyOf(missingEvidenceIds);
        }
    }

    private static final class MutableEvidence {
        private final Map<String, Integer> metrics = new LinkedHashMap<>();
        private final Set<String> flags = new LinkedHashSet<>();
    }
}
