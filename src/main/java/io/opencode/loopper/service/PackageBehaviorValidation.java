package io.opencode.loopper.service;

import static io.opencode.loopper.service.PackageBehaviorContract.*;
import java.util.*;

/** Reject malformed or oversized formulas before recursion, enumeration or solver allocation. */
final class PackageBehaviorValidation {
    private PackageBehaviorValidation() { }
    static List<String> validate(PackageBehaviorContract book, Set<String> sources) {
        var errors = new ArrayList<String>();
        if (book == null || !VERSION.equals(book.version())) return List.of("version must be " + VERSION);
        if (book.variables() == null || book.variables().isEmpty() || book.variables().size() > 32
                || book.obligations() == null || book.obligations().isEmpty() || book.obligations().size() > 64
                || book.invariants() == null || book.invariants().size() > 32 || book.unmodeledSources() == null)
            return List.of("require 1..32 variables, 1..64 obligations, 0..32 invariants and unmodeledSources");
        var vars = new LinkedHashMap<String, Variable>();
        var covered = new HashSet<String>();
        for (var v : book.variables()) {
            if (v == null || !key(v.key()) || vars.putIfAbsent(v.key(), v) != null) { errors.add("duplicate/invalid variable"); continue; }
            if (!Set.of("BOOLEAN", "ENUM", "INTEGER").contains(Objects.toString(v.type(), ""))
                    || !Set.of("INPUT", "OUTPUT").contains(Objects.toString(v.role(), ""))) errors.add("invalid type/role: " + v.key());
            if (v.values() == null || v.values().isEmpty() || v.values().size() > 32
                    || v.values().stream().anyMatch(x -> x == null || x.isBlank() || x.length() > 80)
                    || new HashSet<>(v.values()).size() != v.values().size()) errors.add("invalid finite domain: " + v.key());
            else if ("BOOLEAN".equals(v.type()) && !new HashSet<>(v.values()).equals(Set.of("true", "false"))) errors.add("BOOLEAN domain: " + v.key());
            else if ("INTEGER".equals(v.type()) && v.values().stream().anyMatch(x -> !x.matches("-?(0|[1-9][0-9]{0,8})"))) errors.add("INTEGER domain: " + v.key());
            refs(v.sourceRefs(), sources, errors);
        }
        if (vars.values().stream().noneMatch(v -> "OUTPUT".equals(v.role()))) errors.add("observable OUTPUT required");
        var keys = new HashSet<String>();
        for (var o : book.obligations()) {
            if (o == null || !key(o.key()) || !keys.add(o.key())) { errors.add("duplicate/invalid obligation"); continue; }
            refs(o.sourceRefs(), sources, errors); if (o.sourceRefs() != null) covered.addAll(o.sourceRefs());
            rule(o.event(), o.when(), o.effects(), vars, errors);
        }
        for (var i : book.invariants()) {
            if (i == null || !key(i.key()) || !keys.add(i.key())) { errors.add("duplicate/invalid invariant"); continue; }
            refs(i.sourceRefs(), sources, errors); if (i.sourceRefs() != null) covered.addAll(i.sourceRefs());
            expression(i.predicate(), vars, true, 0, new int[]{0}, errors);
        }
        if (!sources.containsAll(book.unmodeledSources()) || new HashSet<>(book.unmodeledSources()).size() != book.unmodeledSources().size())
            errors.add("invalid unmodeledSources");
        covered.addAll(book.unmodeledSources());
        if (!covered.containsAll(sources)) errors.add("every original source must be modeled or explicitly unmodeled");
        return List.copyOf(errors);
    }
    static List<String> branches(PackageBehaviorContract book, List<Branch> branches, Set<String> scenarios) {
        if (branches == null || branches.isEmpty() || branches.size() > 64) return List.of("require 1..64 behavior branches");
        var errors = new ArrayList<String>(); var vars = new LinkedHashMap<String, Variable>();
        book.variables().forEach(v -> vars.put(v.key(), v));
        var obligations = new HashSet<String>(); book.obligations().forEach(o -> obligations.add(o.key()));
        var bound = new HashSet<String>();
        for (var b : branches) {
            if (b == null) { errors.add("null branch"); continue; }
            if (!scenarios.contains(b.scenarioKey()) || !bound.add(b.scenarioKey())) errors.add("scenarioKey must bind a unique scenario: " + b.scenarioKey());
            if (b.obligationRefs() == null || b.obligationRefs().isEmpty() || !obligations.containsAll(b.obligationRefs())
                    || new HashSet<>(b.obligationRefs()).size() != b.obligationRefs().size()) errors.add("invalid obligationRefs: " + b.scenarioKey());
            rule(b.event(), b.when(), b.effects(), vars, errors);
        }
        return List.copyOf(errors);
    }
    private static void rule(String event, Expr guard, Map<String, String> effects, Map<String, Variable> vars, List<String> errors) {
        if (!key(event)) errors.add("event must be a stable key");
        expression(guard, vars, false, 0, new int[]{0}, errors);
        var outputs = new HashSet<String>(); vars.values().stream().filter(v -> "OUTPUT".equals(v.role())).forEach(v -> outputs.add(v.key()));
        if (effects == null || !effects.keySet().equals(outputs)) { errors.add("effects must assign every OUTPUT exactly once"); return; }
        effects.forEach((name, value) -> { var v = vars.get(name); if (v.values() == null || !v.values().contains(value)) errors.add("effect outside domain: " + name); });
    }
    private static void expression(Expr e, Map<String, Variable> vars, boolean outputs, int depth, int[] count, List<String> errors) {
        if (e == null || depth > 6 || ++count[0] > 128) { errors.add("expression limited to depth 6 / 128 nodes"); return; }
        if (e.args() == null || e.args().size() > 32) { errors.add("expression args required, max 32"); return; }
        switch (Objects.toString(e.op(), "")) {
            case "eq", "ne" -> {
                var v = vars.get(e.variable());
                if (v == null || !outputs && !"INPUT".equals(v.role()) || v.values() == null || !v.values().contains(e.value()) || !e.args().isEmpty())
                    errors.add("invalid atom or post-state used as precondition: " + e.variable());
            }
            case "all", "any", "not", "true", "false" -> {
                if (e.variable() != null || e.value() != null || "not".equals(e.op()) && e.args().size() != 1
                        || Set.of("true", "false").contains(e.op()) && !e.args().isEmpty()
                        || Set.of("all", "any").contains(e.op()) && e.args().isEmpty()) errors.add("invalid expression operator arity");
                e.args().forEach(a -> expression(a, vars, outputs, depth + 1, count, errors));
            }
            default -> errors.add("unsupported operator");
        }
    }
    private static boolean key(String value) { return value != null && value.matches("[A-Za-z][A-Za-z0-9_.-]{0,63}"); }
    private static void refs(List<String> refs, Set<String> sources, List<String> errors) {
        if (refs == null || refs.isEmpty() || refs.size() > 128 || !sources.containsAll(refs) || new HashSet<>(refs).size() != refs.size()) errors.add("invalid frozen sourceRefs");
    }
}
