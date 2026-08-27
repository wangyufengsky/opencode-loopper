package io.opencode.loopper.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectMethod;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.platform.engine.DiscoverySelector;
import org.junit.platform.launcher.Launcher;
import org.junit.platform.launcher.LauncherDiscoveryRequest;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import tools.jackson.databind.ObjectMapper;

class DesignerAcceptanceV7GoldenCorpusTest {
    private static final String SCHEMA = "WEAK_MODEL_COMPILER_V7_GOLDEN_CORPUS_V2";
    private static final String RESOURCE = "/designer-acceptance-v7/golden-corpus-v2.json";
    private static final Path REPORT = Path.of("target", "weak-model-compiler-v7-report.json");
    private static final Path SHADOW_REPORT =
            Path.of("target", "weak-model-compiler-v7-readonly-shadow.json");
    private static final Path QUALIFICATION_REPORT =
            Path.of("target", "weak-model-compiler-v7-qualification.json");
    private static final String SHADOW_GUARD = "io.opencode.loopper.service.DesignerAcceptanceReadOnlyShadowTest"
            + "#comparesV6AndV7FromTheSameFrozenInputWithoutPersistenceOrModelSessions";
    private static final Set<String> METRIC_GUARDS = Set.of(
            "io.opencode.loopper.api.DesignerSessionMcpIntegrationTest"
                    + "#largeV7PackagesCompileServerDirectWithoutChangingFrozenStageTopology",
            "io.opencode.loopper.api.DesignerSessionMcpIntegrationTest"
                    + "#frozenV6LargePackagesKeepTheirSingleCompilerCompatibilityPass",
            "io.opencode.loopper.api.DesignerSessionMcpIntegrationTest"
                    + "#v7AcceptanceAmbiguityCreatesExactlyOneLockedClosedChoiceSession");
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void versionedCorpusExecutesExactGuardsAndPublishesOnlyExpectedMetrics() throws Exception {
        Corpus corpus = readCorpus();

        assertThat(corpus.schemaVersion()).isEqualTo(SCHEMA);
        assertThat(corpus.synthetic()).isTrue();
        assertThat(corpus.samples()).hasSizeGreaterThanOrEqualTo(22);
        assertRequiredCoverage(corpus.samples());
        DesignerAcceptanceV7MeasurementRegistry.clear();
        GuardExecution guardExecution = executeExactGuardTests(corpus.samples());
        GuardExecution metricGuardExecution = executeGuardTests(METRIC_GUARDS);
        GuardExecution shadowExecution = executeGuardTests(Set.of(SHADOW_GUARD));
        var shadowReport = json.readTree(SHADOW_REPORT.toFile());
        assertThat(shadowReport.path("authoritativeMeasurement").asBoolean()).isTrue();
        assertThat(shadowReport.path("completeQualification").asBoolean()).isFalse();
        boolean shadowMeasurementPassed = shadowReport.path("measurement").path("passed").asBoolean();
        assertThat(shadowMeasurementPassed).isTrue();
        List<DesignerAcceptanceV7MeasurementRegistry.Evidence> measuredEvidence =
                DesignerAcceptanceV7MeasurementRegistry.snapshot();
        assertMeasuredEvidence(measuredEvidence);

        corpus.samples().forEach(this::assertExpectedObservationBounds);
        ExpectedSummary expected = summarizeExpected(corpus.samples());
        assertThat(expected.v7CompilerModelCalls()).isLessThanOrEqualTo(expected.v6CompilerModelCalls());
        assertThat(expected.v7FullRedesigns()).isLessThanOrEqualTo(expected.v6FullRedesigns());

        EvaluationReport report = new EvaluationReport(corpus.schemaVersion(), true, false,
                "corpus observations are versioned expectations, not measurements and not an authoritative gate; "
                        + "exact production guards and same-input shadow are executable evidence",
                guardExecution,
                expected, corpus.samples());
        Files.createDirectories(REPORT.getParent());
        Files.write(REPORT, json.writerWithDefaultPrettyPrinter().writeValueAsBytes(report));
        String persisted = Files.readString(REPORT);
        assertRedacted(persisted);

        List<String> guardedCategories = corpus.samples().stream().map(Sample::category).distinct().sorted().toList();
        List<String> guardedCompatibility = corpus.samples().stream()
                .flatMap(sample -> sample.requiredCompatibility().stream()).distinct().sorted().toList();
        boolean qualificationPassed = guardExecution.failed() == 0
                && metricGuardExecution.failed() == 0
                && shadowExecution.failed() == 0 && shadowMeasurementPassed;
        assertThat(qualificationPassed).isTrue();
        QualificationReport qualification = new QualificationReport(true, qualificationPassed, false,
                "complete local qualification requires all exact production guards and the authoritative "
                        + "same-input production-pipeline measurement; corpus expectations are not measurements",
                guardExecution, metricGuardExecution, shadowExecution, shadowMeasurementPassed,
                guardedCategories, guardedCompatibility, measuredEvidence);
        Files.write(QUALIFICATION_REPORT,
                json.writerWithDefaultPrettyPrinter().writeValueAsBytes(qualification));
        assertRedacted(Files.readString(QUALIFICATION_REPORT));
    }

    private Corpus readCorpus() throws Exception {
        try (InputStream input = getClass().getResourceAsStream(RESOURCE)) {
            assertThat(input).as("golden corpus resource").isNotNull();
            return json.readValue(input, Corpus.class);
        }
    }

    private GuardExecution executeExactGuardTests(List<Sample> samples) {
        Set<String> guardTests = samples.stream().map(Sample::guardTest)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        return executeGuardTests(guardTests);
    }

    private GuardExecution executeGuardTests(Set<String> guardTests) {
        List<DiscoverySelector> selectors = guardTests.stream().<DiscoverySelector>map(reference -> {
            String[] parts = reference.split("#", 2);
            assertThat(parts).as("guard test format for %s", reference).hasSize(2);
            return selectMethod(parts[0], parts[1]);
        }).toList();
        LauncherDiscoveryRequest request = LauncherDiscoveryRequestBuilder.request()
                .selectors(selectors).build();
        SummaryGeneratingListener listener = new SummaryGeneratingListener();
        Launcher launcher = LauncherFactory.create();
        launcher.registerTestExecutionListeners(listener);
        launcher.execute(request);
        var summary = listener.getSummary();
        assertThat(summary.getTestsFoundCount()).isEqualTo(guardTests.size());
        assertThat(summary.getTestsFailedCount()).isZero();
        assertThat(summary.getTestsSucceededCount()).isEqualTo(guardTests.size());
        return new GuardExecution(guardTests.size(), Math.toIntExact(summary.getTestsSucceededCount()),
                Math.toIntExact(summary.getTestsFailedCount()));
    }

    private void assertExpectedObservationBounds(Sample sample) {
        for (Observation expected : List.of(sample.v6(), sample.v7())) {
            assertThat(expected.compilerCalls()).isNotNegative();
            assertThat(expected.redesigns()).isNotNegative();
            assertThat(expected.eligibleObligations()).isNotNegative();
            assertThat(expected.conservedObligations()).isBetween(0, expected.eligibleObligations());
            assertThat(expected.acceptance()).isNotNegative();
            assertThat(expected.judgeOnly()).isBetween(0, expected.acceptance());
            assertThat(expected.focusedRequired()).isNotNegative();
            assertThat(expected.focusedCovered()).isBetween(0, expected.focusedRequired());
            assertThat(expected.pathEscapes()).isNotNegative();
            assertThat(expected.hardGaps()).isNotNegative();
            assertThat(expected.blockedHardGaps()).isBetween(0, expected.hardGaps());
            assertThat(expected.dangerousAutoAuthorizations()).isNotNegative();
        }
        assertThat(sample.expectedSafety()).isNotNull();
        assertThat(sample.expectedSafety().mutationObligationCount()).isNotNegative();
        assertThat(sample.expectedSafety().hardGapCount()).isNotNegative();
        if (sample.expectedSafety().resolvedMutationObligationCount() != null
                || sample.expectedSafety().unresolvedMutationObligationCount() != null) {
            assertThat(sample.expectedSafety().resolvedMutationObligationCount()).isNotNull().isNotNegative();
            assertThat(sample.expectedSafety().unresolvedMutationObligationCount()).isNotNull().isNotNegative();
            assertThat(sample.expectedSafety().resolvedMutationObligationCount()
                    + sample.expectedSafety().unresolvedMutationObligationCount())
                    .isEqualTo(sample.expectedSafety().mutationObligationCount());
        }
        if ("ambiguous-stage-owner".equals(sample.id())) {
            assertThat(sample.v7().eligibleObligations())
                    .isEqualTo(sample.expectedSafety().mutationObligationCount());
            assertThat(sample.v7().conservedObligations())
                    .isEqualTo(sample.expectedSafety().resolvedMutationObligationCount());
        }
    }

    private static void assertMeasuredEvidence(
            List<DesignerAcceptanceV7MeasurementRegistry.Evidence> evidence) {
        Map<String, DesignerAcceptanceV7MeasurementRegistry.Evidence> byId = evidence.stream()
                .collect(java.util.stream.Collectors.toMap(
                        DesignerAcceptanceV7MeasurementRegistry.Evidence::evidenceId, item -> item));
        assertThat(byId).containsKeys(
                "same-input-production-pipeline", "server-direct-path-conservation",
                "ambiguous-stage-safety", "large-package-v6-v7-cost",
                "capability-resolution", "closed-choice-workflow-calls",
                "external-system-write-safety");

        var sameInput = byId.get("same-input-production-pipeline");
        assertThat(metric(sameInput, "v7Executable")).isGreaterThanOrEqualTo(metric(sameInput, "v6Executable"));
        assertThat(metric(sameInput, "v7CompilerCalls"))
                .isLessThanOrEqualTo(metric(sameInput, "v6CompilerCalls"));
        assertThat(metric(sameInput, "v7Redesigns")).isLessThanOrEqualTo(metric(sameInput, "v6Redesigns"));
        assertThat(metric(sameInput, "v7JudgeOnly")).isLessThanOrEqualTo(metric(sameInput, "v6JudgeOnly"));
        assertThat(coverage(sameInput, "v7FocusedCovered", "v7FocusedRequired"))
                .isGreaterThanOrEqualTo(coverage(sameInput, "v6FocusedCovered", "v6FocusedRequired"));
        assertThat(metric(sameInput, "v7PathEscapes")).isZero();
        assertThat(metric(sameInput, "v7DangerousAutoAuthorizations")).isZero();

        var serverDirect = byId.get("server-direct-path-conservation");
        assertThat(metric(serverDirect, "v7MutationResolved"))
                .isEqualTo(metric(serverDirect, "v7MutationTotal"));
        assertThat(metric(serverDirect, "v7MutationUnresolved")).isZero();
        assertThat(metric(serverDirect, "v7CompilerCalls")).isZero();
        assertThat(metric(serverDirect, "v7Redesigns")).isZero();

        var ambiguous = byId.get("ambiguous-stage-safety");
        assertThat(metric(ambiguous, "v7MutationTotal")).isEqualTo(4);
        assertThat(metric(ambiguous, "v7MutationResolved")).isEqualTo(2);
        assertThat(metric(ambiguous, "v7MutationUnresolved")).isEqualTo(2);
        assertThat(metric(ambiguous, "v7BlockedHardGaps")).isEqualTo(metric(ambiguous, "v7HardGaps"));
        assertThat(metric(ambiguous, "v7CompilerCalls")).isZero();
        assertThat(metric(ambiguous, "v7Redesigns")).isZero();

        var cost = byId.get("large-package-v6-v7-cost");
        assertThat(metric(cost, "v7CompilerCalls")).isLessThan(metric(cost, "v6CompilerCalls"));
        assertThat(metric(cost, "v7Redesigns")).isLessThanOrEqualTo(metric(cost, "v6Redesigns"));

        var capability = byId.get("capability-resolution");
        assertThat(metric(capability, "v7UniqueOptimumRequiredCompilerCalls")).isZero();
        assertThat(metric(capability, "v7DeterministicWinnerRequiredCompilerCalls")).isZero();
        assertThat(metric(capability, "v7TrueTieRequiredCompilerCalls")).isEqualTo(1);
        assertThat(metric(capability, "v7TrueTieOptimalSolutions")).isEqualTo(2);

        var closedChoice = byId.get("closed-choice-workflow-calls");
        assertThat(metric(closedChoice, "actualCompilerCalls")).isEqualTo(1);
        assertThat(metric(closedChoice, "actualCompilerSessions")).isEqualTo(1);

        var external = byId.get("external-system-write-safety");
        assertThat(metric(external, "blockedRequests")).isPositive();
        assertThat(metric(external, "unsafeRequestsAllowed")).isZero();
    }

    private static int metric(DesignerAcceptanceV7MeasurementRegistry.Evidence evidence, String name) {
        assertThat(evidence.metrics()).containsKey(name);
        return evidence.metrics().get(name);
    }

    private static double coverage(DesignerAcceptanceV7MeasurementRegistry.Evidence evidence,
                                   String covered, String required) {
        int denominator = metric(evidence, required);
        return denominator == 0 ? 1.0d : (double) metric(evidence, covered) / denominator;
    }

    private static void assertRedacted(String persisted) {
        assertThat(persisted)
                .doesNotContain("/Users/", "/home/", "/tmp/", "src/", "frontend/", "docs/", "scripts/",
                        "requirementText", "modelOutput", "sessionId", "externalSessionId")
                .doesNotMatch("(?s).*[A-Za-z]:\\\\.*")
                .doesNotMatch("(?s).*[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-[89abAB]"
                        + "[0-9a-fA-F]{3}-[0-9a-fA-F]{12}.*");
    }

    private ExpectedSummary summarizeExpected(List<Sample> samples) {
        int v6Compiled = (int) samples.stream().filter(sample -> sample.v6().compiled()).count();
        int v7Compiled = (int) samples.stream().filter(sample -> sample.v7().compiled()).count();
        int v6Executable = (int) samples.stream().filter(sample -> sample.v6().executable()).count();
        int v7Executable = (int) samples.stream().filter(sample -> sample.v7().executable()).count();
        int v6Calls = samples.stream().mapToInt(sample -> sample.v6().compilerCalls()).sum();
        int v7Calls = samples.stream().mapToInt(sample -> sample.v7().compilerCalls()).sum();
        int v6Redesigns = samples.stream().mapToInt(sample -> sample.v6().redesigns()).sum();
        int v7Redesigns = samples.stream().mapToInt(sample -> sample.v7().redesigns()).sum();
        return new ExpectedSummary(samples.size(), v6Compiled, v7Compiled, v6Executable, v7Executable,
                v6Calls, v7Calls, v6Redesigns, v7Redesigns);
    }

    private static void assertRequiredCoverage(List<Sample> samples) {
        Set<String> categories = samples.stream().map(Sample::category)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        assertThat(categories).contains(
                "PATH_OMISSION", "UNIQUE_STAGE", "MULTIPLE_STAGE", "FORBIDDEN_OVERLAP",
                "PROJECT_ROOT_OUTSIDE", "DELETE_SOURCE", "RENAME_SOURCE", "UNIQUE_CAPABILITY_OPTIMUM",
                "TRUE_CAPABILITY_TIE", "WEAK_MODEL_ALIAS", "WEAK_MODEL_EXTRA_FIELD",
                "WEAK_MODEL_OMISSION", "WEAK_MODEL_DUPLICATE", "WEAK_MODEL_OUT_OF_RANGE",
                "WEAK_MODEL_DANGEROUS_FIELD", "STACK_COMPATIBILITY", "ROLLING_COMPATIBILITY",
                "HISTORICAL_COMPATIBILITY", "SQLITE_COMPATIBILITY", "RESTART_COMPATIBILITY",
                "EXTERNAL_SYSTEM_WRITE");
        assertThat(samples).extracting(Sample::stack).contains("java", "node", "python");
        assertThat(samples).extracting(Sample::workflow).contains("direct-wp1", "packaged", "rolling", "historical");
        Set<String> compatibility = samples.stream().flatMap(sample -> sample.requiredCompatibility().stream())
                .collect(java.util.stream.Collectors.toSet());
        assertThat(compatibility).isEqualTo(DesignerAcceptanceShadowEvaluator.REQUIRED_COMPATIBILITY);
    }

    record Corpus(String schemaVersion, boolean synthetic, List<Sample> samples) {
        Corpus {
            samples = samples == null ? List.of() : List.copyOf(samples);
        }
    }

    record Sample(String id, String category, String stack, String workflow, String guardTest,
                  boolean targetImprovement, Set<String> requiredCompatibility,
                  SafetyBaseline expectedSafety,
                  Observation v6, Observation v7) {
        Sample {
            requiredCompatibility = requiredCompatibility == null ? Set.of() : Set.copyOf(requiredCompatibility);
        }
    }

    record Observation(boolean compiled, boolean executable, int compilerCalls, int redesigns,
                       int eligibleObligations, int conservedObligations, int acceptance, int judgeOnly,
                       int focusedRequired, int focusedCovered, int stagePathRules, int capabilities,
                       int pathEscapes, int hardGaps, int blockedHardGaps, int dangerousAutoAuthorizations,
                       Set<String> gapCodes, Set<String> compatibilityPassed) {
        Observation {
            gapCodes = gapCodes == null ? Set.of() : Set.copyOf(gapCodes);
            compatibilityPassed = compatibilityPassed == null ? Set.of() : Set.copyOf(compatibilityPassed);
        }

    }

    record SafetyBaseline(int mutationObligationCount,
                          Integer resolvedMutationObligationCount,
                          Integer unresolvedMutationObligationCount,
                          int hardGapCount) { }

    record GuardExecution(int selected, int succeeded, int failed) { }

    record ExpectedSummary(int sampleCount, int v6Compiled, int v7Compiled,
                           int v6Executable, int v7Executable,
                           int v6CompilerModelCalls, int v7CompilerModelCalls,
                           int v6FullRedesigns, int v7FullRedesigns) { }

    record EvaluationReport(String schemaVersion, boolean synthetic, boolean authoritativeGate, String boundary,
                            GuardExecution guardExecution,
                            ExpectedSummary expectedSummary, List<Sample> expectations) { }

    record QualificationReport(boolean authoritativeGate, boolean passed,
                               boolean expectedMetricsUsedAsMeasurements, String boundary,
                               GuardExecution corpusGuardExecution,
                               GuardExecution metricGuardExecution,
                               GuardExecution sameInputMeasurementExecution,
                               boolean sameInputMeasurementPassed,
                               List<String> guardedCategories,
                               List<String> guardedCompatibility,
                               List<DesignerAcceptanceV7MeasurementRegistry.Evidence> measuredEvidence) { }
}
