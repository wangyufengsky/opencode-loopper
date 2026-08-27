package io.opencode.loopper.service;

import static io.opencode.loopper.service.DesignerAcceptancePlanning.Capability;
import static io.opencode.loopper.service.DesignerSemanticContracts.CompactAcceptanceBindingPlan;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class DesignerAcceptanceCapabilitySolverTest {
    private final DesignerAcceptanceCapabilitySolver solver = new DesignerAcceptanceCapabilitySolver();

    @Test
    void comparesEveryBusinessScoreDimensionBeforeUsingCapabilityCount() {
        Capability broadJudge = new Capability(0, "JUDGE", "宽泛人工评审", List.of(), List.of(0, 1),
                List.of(), false, false, 10);
        Capability strongFirst = capability(1, List.of(0), true, false, 80);
        Capability strongSecond = capability(2, List.of(1), true, false, 80);

        var result = solver.solveV7(List.of(0, 1), List.of(broadJudge, strongFirst, strongSecond),
                new CompactAcceptanceBindingPlan(null, List.of(), List.of(), null));

        assertThat(result.selected()).extracting(Capability::index).containsExactly(1, 2);
        assertThat(result.uniqueOptimum()).isTrue();
        assertThat(result.optimalSolutionCount()).isEqualTo(1);
    }

    @Test
    void keepsLegacyScoreWhileV7AddsDeterminismAsABusinessDimension() {
        Capability strongerLegacy = capability(0, List.of(0), false, false, 100);
        Capability deterministicV7 = capability(1, List.of(0), true, false, 80);
        CompactAcceptanceBindingPlan binding = new CompactAcceptanceBindingPlan(null, List.of(), List.of(), null);

        var legacy = solver.solve(List.of(0), List.of(strongerLegacy, deterministicV7), binding);
        var current = solver.solveV7(List.of(0), List.of(strongerLegacy, deterministicV7), binding);

        assertThat(legacy.selected()).extracting(Capability::index).containsExactly(0);
        assertThat(current.selected()).extracting(Capability::index).containsExactly(1);
    }

    @Test
    void reportsOnlyCapabilitiesThatParticipateInEqualOptimalSolutions() {
        Capability tiedA = capability(0, List.of(0), true, false, 100);
        Capability tiedB = capability(1, List.of(0), true, false, 100);
        Capability weaker = capability(2, List.of(0), true, false, 80);

        var result = solver.solveV7(List.of(0), List.of(tiedA, tiedB, weaker),
                new CompactAcceptanceBindingPlan(null, List.of(), List.of(), null));

        assertThat(result.optimalSolutionCount()).isEqualTo(2);
        assertThat(result.tiedCapabilityIndexesByFact()).containsEntry(0, List.of(0, 1));
    }

    @Test
    void excludesCapabilitiesCommonToEveryOptimalSolutionFromTieChoices() {
        Capability common = capability(0, List.of(0), true, true, 100);
        Capability choiceA = capability(1, List.of(0, 1), true, false, 100);
        Capability choiceB = capability(2, List.of(0, 1), true, false, 100);

        var result = solver.solveV7(List.of(0, 1), List.of(common, choiceA, choiceB),
                new CompactAcceptanceBindingPlan(null, List.of(), List.of(), null));

        assertThat(result.optimalSolutionCount()).isEqualTo(2);
        assertThat(result.tiedCapabilityIndexesByFact()).containsEntry(0, List.of(1, 2))
                .containsEntry(1, List.of(1, 2));
    }

    private static Capability capability(int index, List<Integer> facts, boolean deterministic,
                                         boolean mandatory, int strength) {
        return new Capability(index, "FOCUSED_TEST", "能力 " + index,
                List.of("mvn", "-Dtest=Capability" + index + "Test", "test"), facts,
                List.of("Capability" + index + "Test"), deterministic, mandatory, strength);
    }
}
