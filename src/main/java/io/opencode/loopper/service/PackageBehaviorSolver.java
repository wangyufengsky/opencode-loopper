package io.opencode.loopper.service;

import static io.opencode.loopper.service.PackageBehaviorContract.*;
import java.util.*;

interface PackageBehaviorSolver {
    enum Status { SAT, UNSAT, UNKNOWN }
    record Answer(Status status, Map<String, String> witness, String reason) { }
    Answer solve(List<Variable> variables, Expr formula);

    static boolean evaluate(Expr expr, Map<String, String> assignment) {
        return switch (expr.op()) {
            case "true" -> true;
            case "false" -> false;
            case "eq" -> Objects.equals(assignment.get(expr.variable()), expr.value());
            case "ne" -> !Objects.equals(assignment.get(expr.variable()), expr.value());
            case "all" -> expr.args().stream().allMatch(e -> evaluate(e, assignment));
            case "any" -> expr.args().stream().anyMatch(e -> evaluate(e, assignment));
            case "not" -> !evaluate(expr.args().getFirst(), assignment);
            default -> throw new IllegalArgumentException("Unvalidated operator");
        };
    }

    /** Exhaustive checking is deliberately limited; domain overflow is never an UNSAT proof. */
    final class Enumeration implements PackageBehaviorSolver {
        static final int MAX_ASSIGNMENTS = 4096;
        @Override public Answer solve(List<Variable> variables, Expr formula) {
            var inputs = variables.stream().filter(v -> "INPUT".equals(v.role())).toList();
            long size = 1;
            for (var v : inputs) { size *= v.values().size(); if (size > MAX_ASSIGNMENTS) return new Answer(Status.UNKNOWN, Map.of(), "FINITE_DOMAIN_LIMIT"); }
            for (long ordinal = 0; ordinal < size; ordinal++) {
                long remaining = ordinal; var assignment = new LinkedHashMap<String, String>();
                for (var v : inputs) { assignment.put(v.key(), v.values().get((int) (remaining % v.values().size()))); remaining /= v.values().size(); }
                if (evaluate(formula, assignment)) return new Answer(Status.SAT, Collections.unmodifiableMap(assignment), null);
            }
            return new Answer(Status.UNSAT, Map.of(), null);
        }
    }
}
