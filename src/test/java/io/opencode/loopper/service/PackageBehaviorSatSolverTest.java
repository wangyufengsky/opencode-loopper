package io.opencode.loopper.service;

import static io.opencode.loopper.service.PackageBehaviorContract.*;
import static org.assertj.core.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class PackageBehaviorSatSolverTest {
    final List<Variable> variables = List.of(PackageBehaviorCheckerTest.bool("a"), PackageBehaviorCheckerTest.bool("b"),
            new Variable("state", "ENUM", "INPUT", List.of("PENDING", "CLAIMED", "STOPPING"), List.of("REQ-L001")));
    @Test void satMatchesIndependentExhaustiveOracleAcrossSeededFormulas() {
        var random = new Random(761077); var enumeration = new PackageBehaviorSolver.Enumeration();
        for (int i = 0; i < 200; i++) {
            Expr formula = expression(random, 4);
            var expected = enumeration.solve(variables, formula);
            var actual = new PackageBehaviorSatSolver().solve(variables, formula);
            assertThat(actual.status()).as("seeded formula %s", i).isEqualTo(expected.status());
            if (actual.status() == PackageBehaviorSolver.Status.SAT)
                assertThat(PackageBehaviorSolver.evaluate(formula, actual.witness())).isTrue();
        }
    }
    @Test void oneHotEncodingExcludesImpossibleSimultaneousStates() {
        var result = new PackageBehaviorSatSolver().solve(variables, Expr.all(Expr.eq("state", "PENDING"), Expr.eq("state", "CLAIMED")));
        assertThat(result.status()).isEqualTo(PackageBehaviorSolver.Status.UNSAT);
        assertThat(new PackageBehaviorSatSolver().solve(variables, Expr.all(Expr.not(Expr.eq("state", "PENDING")),
                Expr.not(Expr.eq("state", "CLAIMED")), Expr.not(Expr.eq("state", "STOPPING")))).status()).isEqualTo(PackageBehaviorSolver.Status.UNSAT);
    }
    @Test void largeFiniteDomainNowProvesCoverageWithoutEnumeratingAllAssignments() {
        var book = PackageBehaviorCheckerTest.access(); var vars = new ArrayList<>(book.variables());
        for (int i = 0; i < 20; i++) vars.add(PackageBehaviorCheckerTest.bool("extra" + i));
        book = new PackageBehaviorContract(VERSION, vars, book.obligations(), List.of(), List.of());
        var result = new PackageBehaviorChecker(new PackageBehaviorSatSolver()).check(book, PackageBehaviorCheckerTest.correct(book),
                Set.of("REQ-L001"), Set.of("SC-ALLOW", "SC-DENY"));
        assertThat(result.passed()).as(result.toString()).isTrue();
    }
    @Test void budgetIsReservedBeforeSolvingAndUnknownNeverCountsAsPass() {
        var solver = new PackageBehaviorSatSolver(2000, 1);
        assertThat(solver.solve(variables, Expr.truth()).status()).isEqualTo(PackageBehaviorSolver.Status.SAT);
        assertThat(solver.solve(variables, Expr.truth()).status()).isEqualTo(PackageBehaviorSolver.Status.UNKNOWN);
        assertThat(new PackageBehaviorSatSolver(0, 10).solve(variables, Expr.truth()).status()).isEqualTo(PackageBehaviorSolver.Status.UNKNOWN);
        var book = PackageBehaviorCheckerTest.access();
        assertThat(new PackageBehaviorChecker(new PackageBehaviorSatSolver(2000, 0)).check(book, PackageBehaviorCheckerTest.correct(book),
                Set.of("REQ-L001"), Set.of("SC-ALLOW", "SC-DENY")).passed()).isFalse();
    }
    @Test void equivalentGuardRewritesPreserveAcceptedBehaviorAndNegationMutantsFail() {
        var book = PackageBehaviorCheckerTest.access(); var original = PackageBehaviorCheckerTest.correct(book);
        var rewritten = original.stream().map(b -> new Branch(b.scenarioKey(), b.obligationRefs(), b.event(),
                Expr.not(Expr.not(b.when())), b.effects())).toList();
        var checker = new PackageBehaviorChecker(new PackageBehaviorSatSolver());
        assertThat(checker.check(book, rewritten, Set.of("REQ-L001"), Set.of("SC-ALLOW", "SC-DENY")).passed()).isTrue();
        for (int i = 0; i < 2; i++) {
            var mutant = new ArrayList<>(original); var b = mutant.get(i);
            mutant.set(i, new Branch(b.scenarioKey(), b.obligationRefs(), b.event(), Expr.not(b.when()), b.effects()));
            assertThat(new PackageBehaviorChecker(new PackageBehaviorSatSolver()).check(book, mutant,
                    Set.of("REQ-L001"), Set.of("SC-ALLOW", "SC-DENY")).passed()).isFalse();
        }
    }
    private Expr expression(Random random, int depth) {
        if (depth == 0 || random.nextInt(4) == 0) {
            var v = variables.get(random.nextInt(variables.size()));
            return Expr.eq(v.key(), v.values().get(random.nextInt(v.values().size())));
        }
        return switch (random.nextInt(5)) {
            case 0 -> Expr.not(expression(random, depth - 1));
            case 1 -> Expr.all(expression(random, depth - 1), expression(random, depth - 1));
            case 2 -> Expr.any(expression(random, depth - 1), expression(random, depth - 1));
            case 3 -> Expr.truth();
            default -> new Expr("false", null, null, List.of());
        };
    }
}
