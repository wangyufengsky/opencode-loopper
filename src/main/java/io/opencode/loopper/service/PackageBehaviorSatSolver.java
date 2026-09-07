package io.opencode.loopper.service;

import static io.opencode.loopper.service.PackageBehaviorContract.*;
import java.util.*;
import org.sat4j.core.VecInt;
import org.sat4j.minisat.SolverFactory;
import org.sat4j.specs.ContradictionException;
import org.sat4j.specs.TimeoutException;

/** One-hot finite domains plus Tseitin CNF. Each instance belongs to exactly one candidate check. */
final class PackageBehaviorSatSolver implements PackageBehaviorSolver {
    static final int MAX_QUERIES = 4096;
    static final int MAX_CLAUSES = 40000;
    static final int MAX_LITERALS = 16000;
    private final long deadline;
    private final int queryLimit;
    private int queries;
    PackageBehaviorSatSolver() { this(2000, MAX_QUERIES); }
    PackageBehaviorSatSolver(long millis, int queryLimit) {
        this.deadline = System.nanoTime() + Math.max(0, Math.min(millis, 2000)) * 1_000_000L;
        this.queryLimit = Math.max(0, Math.min(queryLimit, MAX_QUERIES));
    }
    @Override public Answer solve(List<Variable> variables, Expr formula) {
        if (++queries > queryLimit || System.nanoTime() >= deadline) return unknown("SEMANTIC_SOLVER_BUDGET");
        var solver = SolverFactory.newDefault();
        solver.newVar(MAX_LITERALS);
        try {
            var encoder = new Encoding(solver, variables);
            encoder.clause(encoder.formula(formula));
            long remaining = (deadline - System.nanoTime()) / 1_000_000L;
            if (remaining < 1) return unknown("SEMANTIC_SOLVER_BUDGET");
            solver.setTimeoutMs(Math.min(100, remaining));
            if (!solver.isSatisfiable()) return new Answer(Status.UNSAT, Map.of(), null);
            var assignment = new LinkedHashMap<String, String>();
            encoder.atoms.forEach((name, values) -> values.forEach((value, literal) -> {
                if (solver.model(literal)) assignment.put(name, value);
            }));
            // Independently evaluate the returned witness before exposing it as evidence.
            if (assignment.size() != encoder.atoms.size() || !PackageBehaviorSolver.evaluate(formula, assignment))
                return unknown("SEMANTIC_SOLVER_WITNESS_INVALID");
            var relevant = new HashSet<String>(); referenced(formula, relevant);
            assignment.keySet().retainAll(relevant);
            return new Answer(Status.SAT, Collections.unmodifiableMap(assignment), null);
        } catch (ContradictionException contradiction) {
            return new Answer(Status.UNSAT, Map.of(), null);
        } catch (TimeoutException timeout) {
            return unknown("SEMANTIC_SOLVER_TIMEOUT");
        } catch (Limit limit) {
            return unknown("SEMANTIC_SOLVER_ENCODING_LIMIT");
        } finally { solver.expireTimeout(); solver.reset(); }
    }
    private static void referenced(Expr e, Set<String> names) {
        if (e.variable() != null) names.add(e.variable());
        e.args().forEach(a -> referenced(a, names));
    }
    private static Answer unknown(String reason) { return new Answer(Status.UNKNOWN, Map.of(), reason); }
    private static final class Limit extends RuntimeException { private static final long serialVersionUID = 1L; }
    private static final class Encoding {
        final org.sat4j.specs.ISolver solver;
        final Map<String, Map<String, Integer>> atoms = new LinkedHashMap<>();
        int next, clauses, nodes;
        Encoding(org.sat4j.specs.ISolver solver, List<Variable> variables) throws ContradictionException {
            this.solver = solver;
            for (var v : variables) if ("INPUT".equals(v.role())) {
                var values = new LinkedHashMap<String, Integer>();
                for (String value : v.values()) values.put(value, fresh());
                atoms.put(v.key(), values);
                int[] literals = values.values().stream().mapToInt(Integer::intValue).toArray();
                clause(literals); // exactly one value, never all false
                for (int a = 0; a < literals.length; a++) for (int b = a + 1; b < literals.length; b++) clause(-literals[a], -literals[b]);
            }
        }
        int formula(Expr e) throws ContradictionException {
            if (++nodes > MAX_LITERALS) throw new Limit();
            if ("eq".equals(e.op())) return atoms.get(e.variable()).get(e.value());
            if ("ne".equals(e.op())) return -atoms.get(e.variable()).get(e.value());
            if ("not".equals(e.op())) return -formula(e.args().getFirst());
            int result = fresh();
            if ("true".equals(e.op())) { clause(result); return result; }
            if ("false".equals(e.op())) { clause(-result); return result; }
            int[] children = new int[e.args().size()];
            for (int i = 0; i < children.length; i++) children[i] = formula(e.args().get(i));
            boolean all = "all".equals(e.op());
            int[] reverse = new int[children.length + 1]; reverse[0] = all ? result : -result;
            for (int i = 0; i < children.length; i++) {
                clause(all ? -result : result, all ? children[i] : -children[i]);
                reverse[i + 1] = all ? -children[i] : children[i];
            }
            clause(reverse); return result;
        }
        int fresh() { if (++next > MAX_LITERALS) throw new Limit(); return next; }
        void clause(int... literals) throws ContradictionException {
            if (++clauses > MAX_CLAUSES) throw new Limit(); solver.addClause(new VecInt(literals));
        }
    }
}
