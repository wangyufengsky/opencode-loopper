package io.opencode.loopper.service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Aggregates bounded, redacted v6/v7 acceptance-planning observations into a fail-closed rollout decision.
 * The contract intentionally cannot carry prompts, model output, repository paths, or persistence identifiers.
 */
final class DesignerAcceptanceShadowEvaluator {
    static final Set<String> REQUIRED_COMPATIBILITY = Set.of(
            "fresh-sqlite", "upgrade-sqlite", "restart", "direct-wp1", "rolling", "historical-v5-v6");

    GateReport evaluate(List<Comparison> comparisons) {
        List<Comparison> samples = comparisons == null ? List.of() : List.copyOf(comparisons);
        Totals v6 = totals(samples.stream().map(Comparison::v6).toList());
        Totals v7 = totals(samples.stream().map(Comparison::v7).toList());
        LinkedHashSet<String> failures = new LinkedHashSet<>();

        int expectedEligibleObligations = samples.stream()
                .mapToInt(sample -> sample.expectedSafety().eligibleMutationObligations()).sum();
        int expectedHardGaps = samples.stream().mapToInt(sample -> sample.expectedSafety().hardGapCount()).sum();
        Double pathConservation = ratioOrUnavailable(
                v7.conservedMutationObligations(), expectedEligibleObligations);
        Double hardGapRetention = ratioOrUnavailable(v7.blockedHardGaps(), expectedHardGaps);
        Double v6JudgeOnly = ratioOrUnavailable(v6.judgeOnly(), v6.acceptanceCount());
        Double v7JudgeOnly = ratioOrUnavailable(v7.judgeOnly(), v7.acceptanceCount());
        Double v6Focused = ratioOrUnavailable(v6.focusedCovered(), v6.focusedRequired());
        Double v7Focused = ratioOrUnavailable(v7.focusedCovered(), v7.focusedRequired());
        int targetImprovements = (int) samples.stream().filter(Comparison::targetImprovement)
                .filter(sample -> !sample.v6().quality().endToEndExecutable()
                        && sample.v7().quality().endToEndExecutable()).count();

        for (Comparison sample : samples) {
            if (!valid(sample.v6()) || !valid(sample.v7())) {
                failures.add("INVALID_SHADOW_COUNTS:" + sample.sampleId());
                continue;
            }
            Safety v7Safety = sample.v7().safety();
            ExpectedSafety expected = sample.expectedSafety();
            if (v7Safety.eligibleMutationObligations() != expected.eligibleMutationObligations()) {
                failures.add("MUTATION_OBLIGATION_BASELINE_MISMATCH:" + sample.sampleId());
            } else if (v7Safety.conservedMutationObligations() != expected.eligibleMutationObligations()) {
                failures.add("MUTATION_PATH_CONSERVATION_BELOW_100_PERCENT:" + sample.sampleId());
            }
            if (v7Safety.hardGapCount() != expected.hardGapCount()) {
                failures.add("HARD_GAP_BASELINE_MISMATCH:" + sample.sampleId());
            } else if (v7Safety.blockedHardGapCount() != expected.hardGapCount()) {
                failures.add("HARD_GAP_RETENTION_BELOW_100_PERCENT:" + sample.sampleId());
            }
            if (coverage(sample.v7().quality().focusedTestCovered(),
                    sample.v7().quality().focusedTestRequired())
                    < coverage(sample.v6().quality().focusedTestCovered(),
                    sample.v6().quality().focusedTestRequired())) {
                failures.add("FOCUSED_TEST_COVERAGE_DECREASED:" + sample.sampleId());
            }
            if (coverage(sample.v7().quality().judgeOnlyCount(), sample.v7().quality().acceptanceCount())
                    > coverage(sample.v6().quality().judgeOnlyCount(), sample.v6().quality().acceptanceCount())) {
                failures.add("JUDGE_ONLY_RATIO_INCREASED:" + sample.sampleId());
            }
        }

        if (pathConservation != null && pathConservation != 1.0d) {
            failures.add("MUTATION_PATH_CONSERVATION_BELOW_100_PERCENT");
        }
        if (v7.pathEscapes() != 0) failures.add("KNOWN_PATH_ESCAPE_NOT_ZERO");
        if (hardGapRetention != null && hardGapRetention != 1.0d) {
            failures.add("HARD_GAP_RETENTION_BELOW_100_PERCENT");
        }
        if (v7.executable() < v6.executable()) failures.add("END_TO_END_EXECUTABLE_REGRESSION");
        if (samples.stream().anyMatch(Comparison::targetImprovement) && targetImprovements == 0) {
            failures.add("TARGET_SAMPLE_NOT_IMPROVED");
        }
        if (v7.compilerCalls() > v6.compilerCalls()) failures.add("COMPILER_MODEL_CALLS_INCREASED");
        if (v7.fullRedesigns() > v6.fullRedesigns()) failures.add("FULL_REDESIGNS_INCREASED");
        if (v7JudgeOnly != null && v6JudgeOnly != null && v7JudgeOnly > v6JudgeOnly) {
            failures.add("JUDGE_ONLY_RATIO_INCREASED");
        }
        if (v7Focused != null && v6Focused != null && v7Focused < v6Focused) {
            failures.add("FOCUSED_TEST_COVERAGE_DECREASED");
        }
        if (v7.dangerousAutoAuthorizations() != 0) failures.add("DANGEROUS_AUTO_AUTHORIZATION_NOT_ZERO");

        Set<String> required = samples.stream().flatMap(sample -> sample.requiredCompatibility().stream())
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        required.forEach(item -> {
            boolean passed = samples.stream().filter(sample -> sample.requiredCompatibility().contains(item))
                    .anyMatch(sample -> sample.v7().compatibilityPassed().contains(item));
            if (!passed) failures.add("COMPATIBILITY_MISSING:" + item);
        });

        return new GateReport(failures.isEmpty(), samples.size(), v6.compiled(), v7.compiled(),
                v6.executable(), v7.executable(), v6.compilerCalls(), v7.compilerCalls(),
                v6.fullRedesigns(), v7.fullRedesigns(), pathConservation, v7.pathEscapes(),
                hardGapRetention, v6JudgeOnly, v7JudgeOnly, v6Focused, v7Focused,
                v7.dangerousAutoAuthorizations(), targetImprovements, List.copyOf(failures));
    }

    private Totals totals(List<Assessment> assessments) {
        int compiled = 0;
        int executable = 0;
        int acceptance = 0;
        int judgeOnly = 0;
        int focusedRequired = 0;
        int focusedCovered = 0;
        int compilerCalls = 0;
        int redesigns = 0;
        int eligibleObligations = 0;
        int conservedObligations = 0;
        int pathEscapes = 0;
        int hardGaps = 0;
        int blockedHardGaps = 0;
        int dangerousAuthorizations = 0;
        for (Assessment assessment : assessments) {
            Quality quality = assessment.quality();
            Cost cost = assessment.cost();
            Safety safety = assessment.safety();
            if (quality.designCompiled()) compiled++;
            if (quality.endToEndExecutable()) executable++;
            acceptance += quality.acceptanceCount();
            judgeOnly += quality.judgeOnlyCount();
            focusedRequired += quality.focusedTestRequired();
            focusedCovered += quality.focusedTestCovered();
            compilerCalls += cost.compilerModelCalls();
            redesigns += cost.fullRedesigns();
            eligibleObligations += safety.eligibleMutationObligations();
            conservedObligations += safety.conservedMutationObligations();
            pathEscapes += safety.knownPathEscapes();
            hardGaps += safety.hardGapCount();
            blockedHardGaps += safety.blockedHardGapCount();
            dangerousAuthorizations += safety.dangerousAutoAuthorizations();
        }
        return new Totals(compiled, executable, acceptance, judgeOnly, focusedRequired, focusedCovered,
                compilerCalls, redesigns, eligibleObligations, conservedObligations, pathEscapes,
                hardGaps, blockedHardGaps, dangerousAuthorizations);
    }

    private static Double ratioOrUnavailable(int numerator, int denominator) {
        return denominator == 0 ? null : (double) numerator / denominator;
    }

    private static double coverage(int numerator, int denominator) {
        return denominator == 0 ? 1.0d : (double) numerator / denominator;
    }

    private static boolean valid(Assessment assessment) {
        Quality quality = assessment.quality();
        Cost cost = assessment.cost();
        Safety safety = assessment.safety();
        Shape shape = assessment.shape();
        return quality.acceptanceCount() >= 0
                && quality.judgeOnlyCount() >= 0
                && quality.judgeOnlyCount() <= quality.acceptanceCount()
                && quality.focusedTestRequired() >= 0
                && quality.focusedTestCovered() >= 0
                && quality.focusedTestCovered() <= quality.focusedTestRequired()
                && cost.compilerModelCalls() >= 0
                && cost.fullRedesigns() >= 0
                && safety.eligibleMutationObligations() >= 0
                && safety.conservedMutationObligations() >= 0
                && safety.conservedMutationObligations() <= safety.eligibleMutationObligations()
                && safety.knownPathEscapes() >= 0
                && safety.hardGapCount() >= 0
                && safety.blockedHardGapCount() >= 0
                && safety.blockedHardGapCount() <= safety.hardGapCount()
                && safety.dangerousAutoAuthorizations() >= 0
                && shape.stagePathRuleCount() >= 0
                && shape.capabilityCount() >= 0;
    }

    record Quality(boolean designCompiled, boolean endToEndExecutable, int acceptanceCount,
                   int judgeOnlyCount, int focusedTestRequired, int focusedTestCovered) { }

    record Cost(int compilerModelCalls, int fullRedesigns) { }

    record Safety(int eligibleMutationObligations, int conservedMutationObligations, int knownPathEscapes,
                  int hardGapCount, int blockedHardGapCount, int dangerousAutoAuthorizations) { }

    record Shape(int stagePathRuleCount, int capabilityCount, Set<String> designGapCodes) {
        Shape {
            designGapCodes = designGapCodes == null ? Set.of() : Set.copyOf(designGapCodes);
        }
    }

    record Assessment(Quality quality, Cost cost, Safety safety, Shape shape, Set<String> compatibilityPassed) {
        Assessment {
            if (quality == null || cost == null || safety == null || shape == null) {
                throw new IllegalArgumentException("Shadow assessment sections are required");
            }
            compatibilityPassed = compatibilityPassed == null ? Set.of() : Set.copyOf(compatibilityPassed);
        }
    }

    record ExpectedSafety(int eligibleMutationObligations, int hardGapCount) {
        ExpectedSafety {
            if (eligibleMutationObligations < 0 || hardGapCount < 0) {
                throw new IllegalArgumentException("Expected shadow safety baselines cannot be negative");
            }
        }
    }

    record Comparison(String sampleId, boolean targetImprovement, Set<String> requiredCompatibility,
                      ExpectedSafety expectedSafety, Assessment v6, Assessment v7) {
        Comparison(String sampleId, boolean targetImprovement, Set<String> requiredCompatibility,
                   Assessment v6, Assessment v7) {
            this(sampleId, targetImprovement, requiredCompatibility,
                    new ExpectedSafety(Math.max(v6.safety().eligibleMutationObligations(),
                            v7.safety().eligibleMutationObligations()),
                            Math.max(v6.safety().hardGapCount(), v7.safety().hardGapCount())), v6, v7);
        }

        Comparison {
            if (sampleId == null || sampleId.isBlank() || expectedSafety == null || v6 == null || v7 == null) {
                throw new IllegalArgumentException(
                        "Shadow comparison requires a sample id, expected safety baseline, and both assessments");
            }
            requiredCompatibility = requiredCompatibility == null ? Set.of() : Set.copyOf(requiredCompatibility);
        }
    }

    record GateReport(boolean passed, int sampleCount, int v6Compiled, int v7Compiled,
                      int v6Executable, int v7Executable, int v6CompilerModelCalls, int v7CompilerModelCalls,
                      int v6FullRedesigns, int v7FullRedesigns, Double pathConservationRate,
                      int knownPathEscapes, Double hardGapRetentionRate, Double v6JudgeOnlyRatio,
                      Double v7JudgeOnlyRatio, Double v6FocusedTestCoverage, Double v7FocusedTestCoverage,
                      int dangerousAutoAuthorizations, int targetImprovements, List<String> failures) {
        GateReport {
            failures = failures == null ? List.of() : List.copyOf(failures);
        }
    }

    private record Totals(int compiled, int executable, int acceptanceCount, int judgeOnly,
                          int focusedRequired, int focusedCovered, int compilerCalls, int fullRedesigns,
                          int eligibleMutationObligations, int conservedMutationObligations,
                          int pathEscapes, int hardGaps, int blockedHardGaps, int dangerousAutoAuthorizations) { }
}
