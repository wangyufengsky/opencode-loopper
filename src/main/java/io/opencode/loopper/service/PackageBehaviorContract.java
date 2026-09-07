package io.opencode.loopper.service;

import java.util.List;
import java.util.Map;

/** A finite semantic domain. This describes observable behavior, never execution authority. */
public record PackageBehaviorContract(String version, List<Variable> variables, List<Obligation> obligations,
        List<Invariant> invariants, List<String> unmodeledSources) {
    public static final String VERSION = "PACKAGE_BEHAVIOR_V1";
    public record Variable(String key, String type, String role, List<String> values, List<String> sourceRefs) { }
    public record Expr(String op, String variable, String value, List<Expr> args) {
        public static Expr eq(String variable, String value) { return new Expr("eq", variable, value, List.of()); }
        public static Expr all(Expr... args) { return new Expr("all", null, null, List.of(args)); }
        public static Expr any(Expr... args) { return new Expr("any", null, null, List.of(args)); }
        public static Expr not(Expr arg) { return new Expr("not", null, null, List.of(arg)); }
        public static Expr truth() { return new Expr("true", null, null, List.of()); }
    }
    /** Effects use OUTPUT variables; pre-state and next-state must be different variables. */
    public record Obligation(String key, List<String> sourceRefs, String event, Expr when, Map<String, String> effects) { }
    public record Invariant(String key, List<String> sourceRefs, Expr predicate) { }
    public record Branch(String scenarioKey, List<String> obligationRefs, String event, Expr when, Map<String, String> effects) { }
}
