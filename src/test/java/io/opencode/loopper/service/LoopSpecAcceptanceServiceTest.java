package io.opencode.loopper.service;

import io.opencode.loopper.domain.LoopSpec;
import io.opencode.loopper.domain.ImplementationKind;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.groups.Tuple.tuple;

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
    void acceptsUnmappedFullSuiteAsBlockingSupplementalEvidence() {
        LoopSpec.VerifierSpec focused = process("TEST", List.of("mvn", "-Dtest=FooTest", "test"),
                List.of("AC-1"), List.of("FooTest"));
        LoopSpec.VerifierSpec fullSuite = process("TEST", List.of("mvn", "test"), List.of(), List.of());
        LoopSpec.StageSpec stage = new LoopSpec.StageSpec("behavior", List.of(), List.of(), List.of("evidence"),
                List.of(focused, fullSuite), List.of(new LoopSpec.AcceptanceCriterion("AC-1", "observable")),
                null, ImplementationKind.JAVA_PRODUCTION);

        var result = service.assess(new LoopSpec("v2", "project", "goal", "", List.of(stage),
                null, null, null, null), List.of(), false);

        assertThat(result.valid()).isTrue();
        assertThat(result.stageAssessments().getFirst().verifiers().get(1).category())
                .isEqualTo(LoopSpecAcceptanceService.Category.REPORT);
        assertThat(result.stageAssessments().getFirst().verifiers().get(1).blocking()).isTrue();
    }

    @Test
    void separatesMachineJudgeAndBothAcceptancePlanning() {
        LoopSpec.VerifierSpec focusedTest = process("TEST", List.of("mvn", "-Dtest=CacheReloadTaskTest", "test"),
                List.of("AC-BOTH"), List.of("CacheReloadTaskTest"));
        LoopSpec.VerifierSpec safetyGate = verifier("FILE_NOT_EXISTS", List.of());
        LoopSpec.StageSpec stage = new LoopSpec.StageSpec("Java behavior", List.of(), List.of(), List.of("implementation and test"),
                List.of(focusedTest, safetyGate), List.of(
                        criterion("AC-BOTH", "reload delegates to cache manager", "BOTH", "review scheduling semantics", null),
                        criterion("AC-JUDGE", "implementation remains maintainable", "JUDGE", "review design cohesion", "maintainability has no reliable binary assertion")), null,
                ImplementationKind.JAVA_PRODUCTION);

        var result = service.assess(new LoopSpec("v2", "project", "goal", "", List.of(stage), null, null, null, null), List.of(), false);

        assertThat(result.valid()).isTrue();
        assertThat(result.stageAssessments().getFirst().criteria())
                .extracting(LoopSpecAcceptanceService.CriterionAssessment::verificationMode,
                        LoopSpecAcceptanceService.CriterionAssessment::covered,
                        LoopSpecAcceptanceService.CriterionAssessment::machineCovered,
                        LoopSpecAcceptanceService.CriterionAssessment::judgePlanned,
                        LoopSpecAcceptanceService.CriterionAssessment::overallPlanned)
                .containsExactly(tuple("BOTH", true, true, true, true), tuple("JUDGE", false, false, true, true));
    }

    @Test
    void acceptsUnmappedFocusedTestAsJavaStageGateWhenEveryCriterionIsJudgeOnly() {
        LoopSpec.VerifierSpec stageGate = process("TEST",
                List.of("mvn", "-Dtest=ChainEventStateMachineTest", "test"), List.of(),
                List.of("ChainEventStateMachineTest"));
        LoopSpec.StageSpec stage = new LoopSpec.StageSpec("state-machine structure", List.of(), List.of(),
                List.of("state-machine configuration"), List.of(stageGate), List.of(
                criterion("AC-JUDGE-1", "event naming is coherent", "JUDGE",
                        "review lifecycle naming", "naming has no unique binary assertion"),
                criterion("AC-JUDGE-2", "listener wiring is maintainable", "JUDGE",
                        "review listener boundaries", "wiring quality requires human judgment")), null,
                ImplementationKind.JAVA_PRODUCTION);

        var result = service.assess(new LoopSpec("v2", "project", "goal", "", List.of(stage),
                null, null, null, null), List.of(), false);

        assertThat(result.valid()).isTrue();
        assertThat(result.stageAssessments().getFirst().verifiers()).singleElement().satisfies(verifier -> {
            assertThat(verifier.blocking()).isTrue();
            assertThat(verifier.criterionIds()).isEmpty();
        });
        assertThat(result.stageAssessments().getFirst().criteria())
                .allSatisfy(criterion -> assertThat(criterion.overallPlanned()).isTrue());
    }

    @Test
    void rejectsIncompleteOrConflictingJudgePlans() {
        LoopSpec.VerifierSpec focusedTest = process("TEST", List.of("mvn", "-Dtest=FooTest", "test"),
                List.of("AC-1"), List.of("FooTest"));
        LoopSpec.StageSpec stage = new LoopSpec.StageSpec("behavior", List.of(), List.of(), List.of("evidence"),
                List.of(focusedTest), List.of(
                        criterion("AC-1", "observable", "JUDGE", "review it", null),
                        criterion("AC-2", "another result", "BOTH", null, null)), null,
                ImplementationKind.NON_JAVA);

        var result = service.assess(new LoopSpec("v2", "project", "goal", "", List.of(stage), null, null, null, null), List.of(), false);

        assertThat(result.errors()).anyMatch(error -> error.contains("use BOTH instead of JUDGE"));
        assertThat(result.errors()).anyMatch(error -> error.contains("judgeOnlyReason"));
        assertThat(result.errors()).anyMatch(error -> error.contains("judgeRubric"));
        assertThat(result.errors()).anyMatch(error -> error.contains("AC-2") && error.contains("machine coverage"));
    }

    @Test
    void rejectsShellInlineJavaAndMissingTargetBypassAtDesignTime() {
        for (List<String> command : List.of(
                List.of("bash", "-c", "mvn test"),
                List.of("java", "-e", "System.out.println(1)"),
                List.of("mvn test && echo PASS"),
                List.of("mvn", "-Dtest=MissingTest", "-Dsurefire.failIfNoSpecifiedTests=false", "test"))) {
            var result = service.assess(spec(process("TEST", command, List.of("AC-1"), List.of("MissingTest")), null), List.of(), false);
            assertThat(result.valid()).isFalse();
            assertThat(result.errors()).anyMatch(error -> error.contains("command"));
        }
        LoopSpec.VerifierSpec sourceSearch = new LoopSpec.VerifierSpec("PROCESS", List.of("rg", "reload", "src/main/java"),
                null, null, List.of(), List.of(), false, "PASS", null, null, null, null, null, null,
                null, null, null, null, List.of(), List.of("AC-1"), "SELF_CHECK", List.of());
        assertThat(service.assess(spec(sourceSearch, null), List.of(), false).errors())
                .anyMatch(error -> error.contains("source-text search cannot prove runtime behavior"));
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
    void rejectsLookalikeExecutablesAndSplitGradleTestExclusions() {
        for (List<String> command : List.of(
                List.of("mvn-evil", "test"),
                List.of("gradle-helper", "test"),
                List.of("gradle", "test", "-x", "test"),
                List.of("./gradlew", "check", "--exclude-task", ":module:test"),
                List.of("npm", "run", "test:unit", "--if-present"))) {
            var result = service.assess(spec(process("TEST", command, List.of("AC-1"), List.of("FooTest")), null),
                    List.of(), false);
            assertThat(result.valid()).isFalse();
            assertThat(result.errors()).anyMatch(error -> error.contains("command"));
        }
    }

    @Test
    void returnsValidationErrorsForMissingVerifierFieldsWithoutThrowing() {
        LoopSpec.VerifierSpec missingType = new LoopSpec.VerifierSpec(null, List.of(), null, null,
                List.of(), List.of(), false, null, null, null, null, null, null, null,
                null, null, null, null, List.of(), List.of(), null, List.of());
        LoopSpec.VerifierSpec missingPurpose = new LoopSpec.VerifierSpec("PROCESS", List.of("mvn", "test"), null,
                null, List.of(), List.of(), false, null, null, null, null, null, null, null,
                null, null, null, null, List.of(), List.of("AC-1"), null, List.of("FooTest"));

        assertThatCode(() -> service.assess(spec(missingType, null), List.of("verifier type is required"), false))
                .doesNotThrowAnyException();
        var result = service.assess(spec(missingPurpose, null), List.of("processPurpose is required"), false);

        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).contains("processPurpose is required")
                .anyMatch(error -> error.contains("processPurpose") && error.contains("requires"));
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
                List.of(focusedTest, fixedHttp), List.of(new LoopSpec.AcceptanceCriterion("AC-1", "observable")), null,
                ImplementationKind.NON_JAVA);
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

    @Test
    void rejectsConfirmedJudgeContractThatExceedsTheUtf8Budget() {
        List<LoopSpec.AcceptanceCriterion> criteria = new ArrayList<>();
        for (int index = 0; index < 30; index++) {
            criteria.add(criterion("AC-" + index, "需要人工判断的结果 " + index, "JUDGE",
                    "评".repeat(4_000), "没有可靠的确定性断言"));
        }
        LoopSpec.StageSpec stage = new LoopSpec.StageSpec("bounded review", List.of(), List.of(), List.of("evidence"),
                List.of(verifier("FILE_NOT_EXISTS", List.of())), criteria, null, ImplementationKind.NON_JAVA);

        var result = service.assess(new LoopSpec("v2", "project", "goal", "", List.of(stage),
                null, null, null, null), List.of(), false);

        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).anyMatch(error -> error.contains("judgeContract")
                && error.contains(Integer.toString(JudgePromptPolicy.MAX_CONTRACT_UTF8_BYTES)));
    }

    private LoopSpec spec(LoopSpec.VerifierSpec verifier, LoopSpec.VerificationRuntime runtime) {
        LoopSpec.StageSpec stage = new LoopSpec.StageSpec("behavior", List.of(), List.of(), List.of("evidence"),
                List.of(verifier), List.of(new LoopSpec.AcceptanceCriterion("AC-1", "observable result")), runtime,
                ImplementationKind.NON_JAVA);
        return new LoopSpec("v2", "project", "goal", "", List.of(stage), null, null, null, null);
    }

    @Test
    void javaProductionRequiresFocusedUnitTestsMappedToEveryMachineCriterion() {
        LoopSpec.VerifierSpec unfocused = process("TEST", List.of("mvn", "test"),
                List.of("AC-1"), List.of("all"));
        LoopSpec.StageSpec stage = new LoopSpec.StageSpec("Java behavior", List.of(), List.of(), List.of("code"),
                List.of(unfocused), List.of(
                criterion("AC-1", "first behavior", "MACHINE", null, null),
                criterion("AC-2", "second behavior", "BOTH", "review edge cases", null)), null,
                ImplementationKind.JAVA_PRODUCTION);

        var rejected = service.assess(new LoopSpec("v2", "project", "goal", "", List.of(stage),
                null, null, null, null), List.of(), false);

        assertThat(rejected.errors()).anyMatch(error -> error.contains("focused Maven/Gradle PROCESS TEST"));
        assertThat(rejected.errors()).anyMatch(error -> error.contains("AC-2") && error.contains("focused Maven/Gradle unit test"));

        LoopSpec.VerifierSpec focused = process("TEST", List.of("mvn", "-Dtest=FooTest", "test"),
                List.of("AC-1", "AC-2"), List.of("FooTest"));
        LoopSpec.StageSpec corrected = new LoopSpec.StageSpec("Java behavior", List.of(), List.of(), List.of("code"),
                List.of(focused), stage.acceptanceCriteria(), null, ImplementationKind.JAVA_PRODUCTION);
        assertThat(service.assess(new LoopSpec("v2", "project", "goal", "", List.of(corrected),
                null, null, null, null), List.of(), false).valid()).isTrue();
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

    private LoopSpec.AcceptanceCriterion criterion(String id, String description, String mode,
                                                    String rubric, String judgeOnlyReason) {
        return new LoopSpec.AcceptanceCriterion(id, description, mode, rubric, judgeOnlyReason);
    }
}
