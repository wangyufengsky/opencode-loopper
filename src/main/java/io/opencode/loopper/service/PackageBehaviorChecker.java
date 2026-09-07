package io.opencode.loopper.service;

import static io.opencode.loopper.service.PackageBehaviorContract.*;
import static io.opencode.loopper.service.PackageBehaviorSolver.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Bounded counterexample queries over frozen obligations, with no filesystem, network or model calls. */
final class PackageBehaviorChecker {
    record Finding(String issueId, String code, List<String> scenarios, List<String> sourceRefs,
            Map<String, String> counterexample, String expected, String actual, String repair) { }
    record Report(String status, List<Finding> findings) { boolean passed() { return "VERIFIED_WITHIN_DOMAIN".equals(status); } }
    private final PackageBehaviorSolver solver;
    PackageBehaviorChecker(PackageBehaviorSolver solver) { this.solver = solver; }

    Report check(PackageBehaviorContract book, List<Branch> branches, Set<String> sources, Set<String> scenarios) {
        var shape = PackageBehaviorValidation.validate(book, sources);
        if (!shape.isEmpty()) return invalid("FROZEN_BEHAVIOR_INVALID", shape);
        shape = PackageBehaviorValidation.branches(book, branches, scenarios);
        if (!shape.isEmpty()) return invalid("CANDIDATE_BEHAVIOR_INVALID", shape);
        var findings = new ArrayList<Finding>();
        // Frozen source model defects must not be "repaired" by weakening the candidate or changing original requirements.
        for (int a = 0; a < book.obligations().size(); a++) {
            var first = book.obligations().get(a);
            feasibility(book, first.when(), "FROZEN_RULE_UNREACHABLE", List.of(first.key()), first.sourceRefs(), findings);
            invariant(book, first.when(), first.effects(), List.of(first.key()), true, findings);
            for (int b = a + 1; b < book.obligations().size(); b++) {
                var second = book.obligations().get(b);
                if (first.event().equals(second.event()) && !first.effects().equals(second.effects()))
                    query(book, Expr.all(first.when(), second.when()), "FROZEN_RULE_CONFLICT", List.of(first.key(), second.key()),
                            union(first.sourceRefs(), second.sourceRefs()), first.effects().toString(), second.effects().toString(), findings);
            }
        }
        if (!findings.isEmpty()) return new Report("SOURCE_MODEL_UNCONFIRMED", List.copyOf(findings));
        for (int a = 0; a < branches.size(); a++) {
            var first = branches.get(a);
            var refs = book.obligations().stream().filter(o -> first.obligationRefs().contains(o.key())).flatMap(o -> o.sourceRefs().stream()).distinct().toList();
            feasibility(book, first.when(), "SEMANTIC_UNREACHABLE", List.of(first.scenarioKey()), refs, findings);
            invariant(book, first.when(), first.effects(), List.of(first.scenarioKey()), false, findings);
            for (int b = a + 1; b < branches.size(); b++) {
                var second = branches.get(b);
                if (first.event().equals(second.event()) && !first.effects().equals(second.effects()))
                    query(book, Expr.all(first.when(), second.when()), "SEMANTIC_CONFLICT", List.of(first.scenarioKey(), second.scenarioKey()), refs,
                            "overlapping applicable branches have identical observable effects", first.effects() + " / " + second.effects(), findings);
            }
            // No invented event/behavior outside the frozen obligations, even if the model attached a valid reference.
            var permitted = book.obligations().stream().filter(o -> first.event().equals(o.event()) && first.effects().equals(o.effects())
                    && first.obligationRefs().contains(o.key())).map(Obligation::when).toList();
            query(book, Expr.all(first.when(), Expr.not(disjunction(permitted))), "SEMANTIC_UNSUPPORTED", List.of(first.scenarioKey()), refs,
                    "behavior supported by referenced frozen obligation", first.effects().toString(), findings);
        }
        for (var obligation : book.obligations()) {
            var compatible = branches.stream().filter(b -> b.obligationRefs().contains(obligation.key())
                    && b.event().equals(obligation.event()) && b.effects().equals(obligation.effects())).toList();
            query(book, Expr.all(obligation.when(), Expr.not(disjunction(compatible.stream().map(Branch::when).toList()))),
                    "SEMANTIC_UNCOVERED", compatible.stream().map(Branch::scenarioKey).toList(), obligation.sourceRefs(),
                    obligation.key() + ": " + obligation.effects(), "missing applicable behavior", findings);
        }
        return new Report(findings.isEmpty() ? "VERIFIED_WITHIN_DOMAIN" : findings.stream().anyMatch(f -> f.code().equals("SEMANTIC_UNKNOWN")) ? "UNKNOWN" : "REJECTED", List.copyOf(findings));
    }
    private void feasibility(PackageBehaviorContract book, Expr guard, String code, List<String> keys, List<String> refs, List<Finding> findings) {
        if (findings.size() >= 32) return;
        var answer = solver.solve(book.variables(), guard);
        if (answer.status() == Status.UNSAT) add(code, keys, refs, Map.of(), "at least one feasible pre-state", "contradictory precondition", findings);
        else if (answer.status() == Status.UNKNOWN) add("SEMANTIC_UNKNOWN", keys, refs, Map.of(), "bounded proof", answer.reason(), findings);
    }
    private void query(PackageBehaviorContract book, Expr formula, String code, List<String> keys, List<String> refs,
            String expected, String actual, List<Finding> findings) {
        if (findings.size() >= 32) return;
        var answer = solver.solve(book.variables(), formula);
        if (answer.status() == Status.SAT) add(code, keys, refs, answer.witness(), expected, actual, findings);
        else if (answer.status() == Status.UNKNOWN) add("SEMANTIC_UNKNOWN", keys, refs, Map.of(), expected, answer.reason(), findings);
    }
    private void invariant(PackageBehaviorContract book, Expr guard, Map<String, String> effects, List<String> keys, boolean frozen, List<Finding> findings) {
        for (var inv : book.invariants()) query(book, Expr.all(guard, Expr.not(substitute(inv.predicate(), effects))),
                frozen ? "FROZEN_INVARIANT_CONFLICT" : "SEMANTIC_INVARIANT", keys, inv.sourceRefs(), inv.key(), effects.toString(), findings);
    }
    private static Expr substitute(Expr e, Map<String, String> effects) {
        if (Set.of("eq", "ne").contains(e.op()) && effects.containsKey(e.variable())) {
            boolean equal = Objects.equals(effects.get(e.variable()), e.value());
            return new Expr(equal == e.op().equals("eq") ? "true" : "false", null, null, List.of());
        }
        return new Expr(e.op(), e.variable(), e.value(), e.args().stream().map(a -> substitute(a, effects)).toList());
    }
    private static Expr disjunction(List<Expr> expressions) { return expressions.isEmpty() ? new Expr("false", null, null, List.of()) : new Expr("any", null, null, expressions); }
    private static List<String> union(List<String> a, List<String> b) { return java.util.stream.Stream.concat(a.stream(), b.stream()).distinct().toList(); }
    private static Report invalid(String code, List<String> errors) {
        var findings = new ArrayList<Finding>(); add(code, List.of(), List.of(), Map.of(), "bounded typed semantic contract", String.join("; ", errors), findings);
        return new Report("INVALID", findings);
    }
    private static void add(String code, List<String> keys, List<String> refs, Map<String, String> witness, String expected, String actual, List<Finding> findings) {
        String identity = code + "|" + new TreeSet<>(keys) + "|" + new TreeSet<>(refs) + "|" + new TreeMap<>(witness);
        String id = "SEM-" + PackageDesignEvidencePreparation.hash(identity.getBytes(StandardCharsets.UTF_8)).substring(0, 20);
        if (findings.stream().anyMatch(f -> f.issueId().equals(id))) return;
        findings.add(new Finding(id, code, List.copyOf(keys), List.copyOf(refs), Map.copyOf(witness), expected, actual,
                code.startsWith("FROZEN_") ? "原文模型尚未确认；通过来源复核或新修订处理，不得修改原文以适配候选"
                        : code.equals("SEMANTIC_UNKNOWN") ? "缩小有限域或拆分独立义务；未知不能作为通过"
                        : "按反例修复适用条件或可观察结果，保留来源和其他分支，提交完整替换对象"));
    }
}
