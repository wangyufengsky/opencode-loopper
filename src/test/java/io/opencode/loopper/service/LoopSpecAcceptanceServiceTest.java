package io.opencode.loopper.service;

import io.opencode.loopper.domain.LoopSpec;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LoopSpecAcceptanceServiceTest {
    private final LoopSpecAcceptanceService service = new LoopSpecAcceptanceService();

    @Test
    void rejectsBuildScopeSafetyReportAndAdvisoryOnlyCoverage() {
        for (LoopSpec.VerifierSpec verifier : List.of(
                process("BUILD", List.of("mvn", "package"), List.of("AC-1"), List.of()),
                verifier("GIT_DIFF", List.of("AC-1")),
                verifier("FILE_NOT_EXISTS", List.of("AC-1")),
                verifier("JUNIT_XML", List.of("AC-1")),
                verifier("FILE_EXISTS", List.of("AC-1")))) {
            var result = service.assess(spec(verifier, null), List.of(), false);
            assertThat(result.valid()).isFalse();
            assertThat(result.errors()).anyMatch(error -> error.contains("no valid BEHAVIOR verifier"));
        }
    }

    @Test
    void acceptsFocusedMavenGradleAndNpmTestsAndSelfChecks() {
        for (LoopSpec.VerifierSpec verifier : List.of(
                process("TEST", List.of("mvn", "test", "-Dtest=FooTest"), List.of("AC-1"), List.of("FooTest")),
                process("TEST", List.of("./gradlew", "test", "--tests", "FooTest"), List.of("AC-1"), List.of("FooTest")),
                process("TEST", List.of("npm", "run", "test:unit", "--", "Foo.spec.ts"), List.of("AC-1"), List.of("Foo.spec.ts")),
                new LoopSpec.VerifierSpec("PROCESS", List.of("java", "-jar", "self-check.jar"), null, null,
                        List.of(), List.of(), false, "SELF_CHECK_PASS", null, null, null, null, null, null,
                        null, null, null, null, List.of(), List.of("AC-1"), "SELF_CHECK", List.of()))) {
            assertThat(service.assess(spec(verifier, null), List.of(), false).valid()).isTrue();
        }
    }

    @Test
    void rejectsSkippedTestsUnknownCriteriaAndUnmanagedNetworkEvidence() {
        var skipped = process("TEST", List.of("mvn", "test", "-DskipTests"), List.of("AC-404"), List.of("FooTest"));
        var result = service.assess(spec(skipped, null), List.of(), false);
        assertThat(result.errors()).anyMatch(error -> error.contains("must not disable or skip tests"));
        assertThat(result.errors()).anyMatch(error -> error.contains("unknown acceptance criterion"));

        LoopSpec.VerifierSpec http = new LoopSpec.VerifierSpec("HTTP_STATUS", null, null, null, List.of(), List.of(),
                false, null, "http://127.0.0.1:8080/health", "GET", 200, null, null, null, null, null,
                null, null, List.of(), List.of("AC-1"), null, List.of());
        assertThat(service.assess(spec(http, null), List.of(), false).errors())
                .anyMatch(error -> error.contains("managed runtime"));
    }

    @Test
    void acceptsNetworkBehaviorBoundToManagedDynamicRuntime() {
        LoopSpec.VerifierSpec http = new LoopSpec.VerifierSpec("HTTP_STATUS", null, null, null, List.of(), List.of(),
                false, null, "http://127.0.0.1:{{LOOPPER_PORT}}/health", "GET", 200, null, null, null,
                null, null, null, null, List.of(), List.of("AC-1"), null, List.of());
        LoopSpec.VerificationRuntime runtime = new LoopSpec.VerificationRuntime(
                List.of("java", "-jar", "app.jar", "--server.port={{LOOPPER_PORT}}"),
                new LoopSpec.RuntimeReadiness("/health", 200, "$.status", "UP", "EXACT"), 30, 5);
        assertThat(service.assess(spec(http, runtime), List.of(), false).valid()).isTrue();
    }

    @Test
    void allowsFixedLoopbackOnlyAsSupplementalEvidence() {
        LoopSpec.VerifierSpec focusedTest = process("TEST", List.of("mvn", "test", "-Dtest=ApiTest"),
                List.of("AC-1"), List.of("ApiTest"));
        LoopSpec.VerifierSpec fixedHttp = new LoopSpec.VerifierSpec("HTTP_STATUS", null, null, null,
                List.of(), List.of(), false, null, "http://127.0.0.1:8080/health", "GET", 200,
                null, null, null, null, null, null, null, List.of(), List.of(), null, List.of());
        LoopSpec.StageSpec stage = new LoopSpec.StageSpec("behavior", List.of(), List.of(), List.of("evidence"),
                List.of(focusedTest, fixedHttp), List.of(new LoopSpec.AcceptanceCriterion("AC-1", "observable")), null);
        LoopSpec spec = new LoopSpec("v2", "project", "goal", "", List.of(stage), null, null, null, null);

        var result = service.assess(spec, List.of(), false);

        assertThat(result.valid()).isTrue();
        assertThat(result.stageAssessments().getFirst().verifiers().get(1).reason())
                .contains("not bound to this stage managed runtime");
    }

    @Test
    void classifiesCompilePackageBuildTypecheckAndLintAsBuildOnly() {
        for (List<String> command : List.of(
                List.of("mvn", "compile"), List.of("mvn", "package"), List.of("./gradlew", "build"),
                List.of("npm", "run", "build"), List.of("npm", "run", "typecheck"),
                List.of("npm", "run", "lint"), List.of("npm", "ci"))) {
            var verifier = process("BUILD", command, List.of("AC-1"), List.of());
            assertThat(service.assess(spec(verifier, null), List.of(), false).errors())
                    .anyMatch(error -> error.contains("no valid BEHAVIOR verifier"));
        }
    }

    @Test
    void preservesPersistedV1ButRejectsNewV1() {
        LoopSpec legacy = new LoopSpec("v1", "project", "goal", "", List.of(new LoopSpec.StageSpec(
                "legacy", List.of(), List.of(), List.of(), List.of(verifier("FILE_EXISTS", List.of())))),
                null, null, null, null);
        assertThat(service.assess(legacy, List.of(), true).valid()).isTrue();
        assertThat(service.assess(legacy, List.of(), false).valid()).isFalse();
    }

    private LoopSpec spec(LoopSpec.VerifierSpec verifier, LoopSpec.VerificationRuntime runtime) {
        LoopSpec.StageSpec stage = new LoopSpec.StageSpec("behavior", List.of(), List.of(), List.of("evidence"),
                List.of(verifier), List.of(new LoopSpec.AcceptanceCriterion("AC-1", "observable result")), runtime);
        return new LoopSpec("v2", "project", "goal", "", List.of(stage), null, null, null, null);
    }

    private LoopSpec.VerifierSpec process(String purpose, List<String> command, List<String> criterionIds,
                                          List<String> targets) {
        return new LoopSpec.VerifierSpec("PROCESS", command, null, null, List.of(), List.of(), false, null,
                null, null, null, null, null, null, null, null, null, null, List.of(), criterionIds, purpose, targets);
    }

    private LoopSpec.VerifierSpec verifier(String type, List<String> criterionIds) {
        String path = List.of("FILE_NOT_EXISTS", "JUNIT_XML", "FILE_EXISTS").contains(type) ? "target/result" : null;
        return new LoopSpec.VerifierSpec(type, null, path, true, List.of(), List.of(), false, null,
                null, null, null, null, null, null, null, null, null, null, List.of(), criterionIds, null, List.of());
    }
}
