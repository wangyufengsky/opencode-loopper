package io.opencode.loopper.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DesignerAcceptanceShadowEvaluatorTest {
    private final DesignerAcceptanceShadowEvaluator evaluator = new DesignerAcceptanceShadowEvaluator();

    @Test
    void rejectsSurfaceCompilerImprovementWhenV7IntroducesAPathEscape() {
        DesignerAcceptanceShadowEvaluator.Assessment v6 = assessment(
                false, false, 1, 1, 0, 0, 2, 0, 0, 0, 0);
        DesignerAcceptanceShadowEvaluator.Assessment v7 = assessment(
                true, true, 0, 0, 2, 2, 2, 1, 0, 0, 0);

        DesignerAcceptanceShadowEvaluator.GateReport report = evaluator.evaluate(List.of(
                new DesignerAcceptanceShadowEvaluator.Comparison("path-escape", true, Set.of(), v6, v7)));

        assertThat(report.passed()).isFalse();
        assertThat(report.failures()).contains("KNOWN_PATH_ESCAPE_NOT_ZERO");
    }

    @Test
    void acceptsImprovementOnlyWhenAllSafetyCostCoverageAndCompatibilityGatesHold() {
        Set<String> compatibility = Set.of("fresh-sqlite", "upgrade-sqlite", "restart",
                "direct-wp1", "rolling", "historical-v5-v6");
        DesignerAcceptanceShadowEvaluator.Assessment v6 = assessment(
                true, false, 1, 1, 0, 0, 2, 0, 2, 2, 0);
        DesignerAcceptanceShadowEvaluator.Assessment v7 = new DesignerAcceptanceShadowEvaluator.Assessment(
                new DesignerAcceptanceShadowEvaluator.Quality(true, true, 2, 0, 2, 2),
                new DesignerAcceptanceShadowEvaluator.Cost(0, 0),
                new DesignerAcceptanceShadowEvaluator.Safety(2, 2, 0, 2, 2, 0),
                new DesignerAcceptanceShadowEvaluator.Shape(2, 1, Set.of()), compatibility);

        DesignerAcceptanceShadowEvaluator.GateReport report = evaluator.evaluate(List.of(
                new DesignerAcceptanceShadowEvaluator.Comparison("safe-improvement", true,
                        compatibility, v6, v7)));

        assertThat(report.passed()).isTrue();
        assertThat(report.v6Executable()).isZero();
        assertThat(report.v7Executable()).isEqualTo(1);
        assertThat(report.pathConservationRate()).isEqualTo(1.0d);
        assertThat(report.hardGapRetentionRate()).isEqualTo(1.0d);
    }

    @Test
    void rejectsWhenV7MakesTheMutationOrHardGapBaselineDisappear() {
        DesignerAcceptanceShadowEvaluator.Assessment v6 = assessment(
                true, true, 0, 0, 1, 1, 1, 0, 1, 1, 0);
        DesignerAcceptanceShadowEvaluator.Assessment missingMutationBaseline = assessment(
                true, true, 0, 0, 0, 0, 1, 0, 1, 1, 0);
        DesignerAcceptanceShadowEvaluator.Assessment missingHardGapBaseline = assessment(
                true, true, 0, 0, 1, 1, 1, 0, 0, 0, 0);

        var mutationReport = evaluator.evaluate(List.of(new DesignerAcceptanceShadowEvaluator.Comparison(
                "missing-mutation-baseline", false, Set.of(), v6, missingMutationBaseline)));
        var hardGapReport = evaluator.evaluate(List.of(new DesignerAcceptanceShadowEvaluator.Comparison(
                "missing-hard-gap-baseline", false, Set.of(), v6, missingHardGapBaseline)));

        assertThat(mutationReport.passed()).isFalse();
        assertThat(mutationReport.failures()).contains("MUTATION_OBLIGATION_BASELINE_MISMATCH:missing-mutation-baseline");
        assertThat(hardGapReport.passed()).isFalse();
        assertThat(hardGapReport.failures()).contains("HARD_GAP_BASELINE_MISMATCH:missing-hard-gap-baseline");
    }

    @Test
    void rejectsPerSampleSafetyAndCoverageRegressionsThatAggregateArithmeticCouldMask() {
        DesignerAcceptanceShadowEvaluator.Assessment baseline = assessment(
                true, true, 0, 0, 1, 1, 2, 0, 1, 1, 0);
        DesignerAcceptanceShadowEvaluator.Assessment underReported = new DesignerAcceptanceShadowEvaluator.Assessment(
                new DesignerAcceptanceShadowEvaluator.Quality(true, true, 2, 0, 2, 1),
                new DesignerAcceptanceShadowEvaluator.Cost(0, 0),
                new DesignerAcceptanceShadowEvaluator.Safety(1, 0, 0, 1, 0, 0),
                new DesignerAcceptanceShadowEvaluator.Shape(1, 1, Set.of()), Set.of());
        DesignerAcceptanceShadowEvaluator.Assessment overReported = new DesignerAcceptanceShadowEvaluator.Assessment(
                new DesignerAcceptanceShadowEvaluator.Quality(true, true, 2, 0, 2, 3),
                new DesignerAcceptanceShadowEvaluator.Cost(0, 0),
                new DesignerAcceptanceShadowEvaluator.Safety(1, 2, 0, 1, 2, 0),
                new DesignerAcceptanceShadowEvaluator.Shape(1, 1, Set.of()), Set.of());

        var report = evaluator.evaluate(List.of(
                new DesignerAcceptanceShadowEvaluator.Comparison("under", false, Set.of(), baseline, underReported),
                new DesignerAcceptanceShadowEvaluator.Comparison("over", false, Set.of(), baseline, overReported)));

        assertThat(report.passed()).isFalse();
        assertThat(report.failures()).contains(
                "MUTATION_PATH_CONSERVATION_BELOW_100_PERCENT:under",
                "HARD_GAP_RETENTION_BELOW_100_PERCENT:under",
                "FOCUSED_TEST_COVERAGE_DECREASED:under",
                "INVALID_SHADOW_COUNTS:over");
    }

    private static DesignerAcceptanceShadowEvaluator.Assessment assessment(
            boolean compiled, boolean executable, int compilerCalls, int redesigns,
            int eligibleObligations, int conservedObligations, int acceptanceCount,
            int pathEscapes, int hardGaps, int blockedHardGaps, int dangerousAuthorizations) {
        return new DesignerAcceptanceShadowEvaluator.Assessment(
                new DesignerAcceptanceShadowEvaluator.Quality(compiled, executable,
                        acceptanceCount, 0, acceptanceCount, acceptanceCount),
                new DesignerAcceptanceShadowEvaluator.Cost(compilerCalls, redesigns),
                new DesignerAcceptanceShadowEvaluator.Safety(eligibleObligations, conservedObligations,
                        pathEscapes, hardGaps, blockedHardGaps, dangerousAuthorizations),
                new DesignerAcceptanceShadowEvaluator.Shape(1, 1, Set.of()), Set.of());
    }
}
