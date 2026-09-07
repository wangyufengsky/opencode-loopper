package io.opencode.loopper.service;

import static io.opencode.loopper.service.PackageBehaviorContract.*;
import static org.assertj.core.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class PackageBehaviorCheckerTest {
    static final List<String> SOURCE = List.of("REQ-L001");
    final PackageBehaviorChecker checker = new PackageBehaviorChecker(new PackageBehaviorSolver.Enumeration());
    static Variable bool(String name) { return new Variable(name, "BOOLEAN", "INPUT", List.of("false", "true"), SOURCE); }
    static Variable output() { return new Variable("result", "ENUM", "OUTPUT", List.of("ALLOW", "DENY"), SOURCE); }
    static Expr yes(String name) { return Expr.eq(name, "true"); }
    static Map<String, String> effect(String value) { return Map.of("result", value); }
    static PackageBehaviorContract access() {
        var allow = Expr.all(Expr.not(yes("archived")), Expr.any(yes("admin"), Expr.not(yes("revoked"))));
        return new PackageBehaviorContract(VERSION, List.of(bool("admin"), bool("revoked"), bool("archived"), output()),
                List.of(new Obligation("ALLOW", SOURCE, "read", allow, effect("ALLOW")),
                        new Obligation("DENY", SOURCE, "read", Expr.not(allow), effect("DENY"))), List.of(), List.of());
    }
    static List<Branch> correct(PackageBehaviorContract book) {
        return book.obligations().stream().map(o -> new Branch("SC-" + o.key(), List.of(o.key()), o.event(), o.when(), o.effects())).toList();
    }
    PackageBehaviorChecker.Report check(PackageBehaviorContract book, List<Branch> branches) {
        return checker.check(book, branches, Set.copyOf(SOURCE), branches.stream().map(Branch::scenarioKey).collect(java.util.stream.Collectors.toSet()));
    }
    @Test void completeAccessTablePassesWithAdminRevocationPriorityAndArchiveOverride() {
        assertThat(check(access(), correct(access())).passed()).isTrue();
    }
    @Test void revokedDenialWithoutAdminExceptionYieldsConcreteOverlapAndUnsupportedBehavior() {
        var bad = new ArrayList<>(correct(access()));
        bad.set(1, new Branch("SC-DENY", List.of("DENY"), "read", yes("revoked"), effect("DENY")));
        var report = check(access(), bad);
        assertThat(report.findings()).extracting(PackageBehaviorChecker.Finding::code).contains("SEMANTIC_CONFLICT", "SEMANTIC_UNSUPPORTED", "SEMANTIC_UNCOVERED");
        var conflict = report.findings().stream().filter(f -> f.code().equals("SEMANTIC_CONFLICT")).findFirst().orElseThrow();
        assertThat(conflict.counterexample()).containsEntry("admin", "true").containsEntry("revoked", "true").containsEntry("archived", "false");
        assertThat(check(access(), correct(access())).passed()).isTrue();
    }
    @Test void denialOnlyCannotPassByDeclaringCoverageReferences() {
        var bad = List.of(new Branch("SC-DENY", List.of("ALLOW", "DENY"), "read", Expr.truth(), effect("DENY")));
        assertThat(check(access(), bad).findings()).extracting(PackageBehaviorChecker.Finding::code).contains("SEMANTIC_UNCOVERED", "SEMANTIC_UNSUPPORTED");
    }
    @Test void initialTransitionCannotBeReplacedByAlreadyStoppingRepeat() {
        var state = new Variable("before", "ENUM", "INPUT", List.of("CLAIMED", "STOPPING"), SOURCE);
        var next = new Variable("after", "ENUM", "OUTPUT", List.of("STOPPING", "CANCELLED"), SOURCE);
        var obligation = new Obligation("CANCEL", SOURCE, "cancel", Expr.eq("before", "CLAIMED"), Map.of("after", "STOPPING"));
        var book = new PackageBehaviorContract(VERSION, List.of(state, next), List.of(obligation), List.of(), List.of());
        var bad = List.of(new Branch("SC-CANCEL", List.of("CANCEL"), "cancel", Expr.eq("before", "STOPPING"), obligation.effects()));
        assertThat(check(book, bad).findings()).extracting(PackageBehaviorChecker.Finding::code).contains("SEMANTIC_UNCOVERED", "SEMANTIC_UNSUPPORTED");
        bad = List.of(new Branch("SC-CANCEL", List.of("CANCEL"), "cancel", Expr.all(Expr.eq("before", "STOPPING"), Expr.eq("before", "CLAIMED")), obligation.effects()));
        assertThat(check(book, bad).findings()).extracting(PackageBehaviorChecker.Finding::code).contains("SEMANTIC_UNREACHABLE");
    }
    @Test void sourceModelContradictionIsNotReportedAsUserBusinessDecisionOrCandidateDefect() {
        var book = access(); var obligations = new ArrayList<>(book.obligations());
        obligations.add(new Obligation("BAD", SOURCE, "read", Expr.truth(), effect("DENY")));
        book = new PackageBehaviorContract(VERSION, book.variables(), obligations, List.of(), List.of());
        assertThat(check(book, correct(book)).status()).isEqualTo("SOURCE_MODEL_UNCONFIRMED");
    }
    @Test void noImplicitDefaultDenyForUnspecifiedInputs() {
        var book = new PackageBehaviorContract(VERSION, List.of(bool("admin"), output()),
                List.of(new Obligation("A", SOURCE, "read", yes("admin"), effect("ALLOW"))), List.of(), List.of());
        assertThat(check(book, correct(book)).passed()).isTrue();
        var invented = new ArrayList<>(correct(book)); invented.add(new Branch("SC-D", List.of("A"), "read", Expr.not(yes("admin")), effect("DENY")));
        assertThat(check(book, invented).findings()).extracting(PackageBehaviorChecker.Finding::code).contains("SEMANTIC_UNSUPPORTED");
    }
    @Test void unknownOnLargeDomainIsNotProofAndNoOverflow() {
        var book = access(); var vars = new ArrayList<>(book.variables());
        for (int i = 0; i < 13; i++) vars.add(bool("extra" + i));
        book = new PackageBehaviorContract(VERSION, vars, book.obligations(), List.of(), List.of());
        var report = check(book, correct(book)); assertThat(report.passed()).isFalse();
        assertThat(report.findings()).extracting(PackageBehaviorChecker.Finding::code).contains("SEMANTIC_UNKNOWN");
    }
    @Test void invariantUsesPreStateAndAssignedPostState() {
        var book = access();
        book = new PackageBehaviorContract(VERSION, book.variables(), book.obligations(),
                List.of(new Invariant("ARCHIVE", SOURCE, Expr.any(Expr.not(yes("archived")), Expr.eq("result", "DENY")))), List.of());
        assertThat(check(book, correct(book)).passed()).isTrue();
        var contradictory = new PackageBehaviorContract(VERSION, book.variables(), book.obligations(),
                List.of(new Invariant("BAD", SOURCE, Expr.eq("result", "ALLOW"))), List.of());
        assertThat(check(contradictory, correct(contradictory)).findings()).extracting(PackageBehaviorChecker.Finding::code).contains("FROZEN_INVARIANT_CONFLICT");
    }
    @Test void postStateCannotBeAssumedInPreconditionAndInvalidAtomsAreRejected() {
        var bad = List.of(new Branch("SC-X", List.of("ALLOW"), "read", Expr.eq("result", "ALLOW"), effect("ALLOW")));
        assertThat(check(access(), bad).status()).isEqualTo("INVALID");
        assertThat(check(access(), List.of(new Branch("SC-X", List.of("ALLOW"), "read", Expr.eq("admin", "maybe"), effect("ALLOW")))).status()).isEqualTo("INVALID");
    }
    @Test void issueIdentitySurvivesReplacementOrdering() {
        var bad = new ArrayList<>(correct(access())); bad.removeFirst();
        var a = check(access(), bad); var b = check(access(), bad);
        assertThat(a.findings()).isEqualTo(b.findings());
        assertThat(a.findings()).allSatisfy(f -> assertThat(f.issueId()).startsWith("SEM-"));
    }
    @Test void everySourceAccountedForButUnmodeledIsExplicitNotProof() {
        var book = access();
        assertThat(checker.check(book, correct(book), Set.of("REQ-L001", "REQ-L002"), Set.of("SC-ALLOW", "SC-DENY")).status()).isEqualTo("INVALID");
        book = new PackageBehaviorContract(VERSION, book.variables(), book.obligations(), List.of(), List.of("REQ-L002"));
        assertThat(checker.check(book, correct(book), Set.of("REQ-L001", "REQ-L002"), Set.of("SC-ALLOW", "SC-DENY")).passed()).isTrue();
    }
    @Test void excessiveDepthNullCollectionsAndInvalidDomainsCannotCrashChecker() {
        var book = access(); Expr expr = Expr.truth(); for (int i = 0; i < 8; i++) expr = Expr.not(expr);
        assertThat(check(book, List.of(new Branch("SC-A", List.of("ALLOW"), "read", expr, effect("ALLOW")))).status()).isEqualTo("INVALID");
        assertThat(checker.check(null, List.of(), Set.of(), Set.of()).status()).isEqualTo("INVALID");
        var invalid = new PackageBehaviorContract(VERSION, List.of(new Variable("x", "BOOLEAN", "INPUT", null, SOURCE), output()), book.obligations(), List.of(), List.of());
        assertThat(check(invalid, correct(book)).status()).isEqualTo("INVALID");
    }
}
