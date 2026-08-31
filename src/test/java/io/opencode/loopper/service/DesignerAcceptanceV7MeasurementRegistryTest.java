package io.opencode.loopper.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class DesignerAcceptanceV7MeasurementRegistryTest {
    @AfterEach
    void clearRegistry() {
        DesignerAcceptanceV7MeasurementRegistry.clear();
    }

    @Test
    void rejectsUnknownEvidenceIdsMetricNamesAndFlags() {
        assertThatThrownBy(() -> DesignerAcceptanceV7MeasurementRegistry.record(
                "session-019dbeef", Map.of("actualCompilerCalls", 1), Set.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DesignerAcceptanceV7MeasurementRegistry.record(
                "closed-choice-workflow-calls", Map.of("sessionId", 1), Set.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DesignerAcceptanceV7MeasurementRegistry.record(
                "closed-choice-workflow-calls", Map.of("actualCompilerCalls", 1),
                Set.of("/Users/example/secret")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNegativeOrConflictingMeasurements() {
        assertThatThrownBy(() -> DesignerAcceptanceV7MeasurementRegistry.record(
                "closed-choice-workflow-calls", Map.of("actualCompilerCalls", -1), Set.of()))
                .isInstanceOf(IllegalArgumentException.class);
        DesignerAcceptanceV7MeasurementRegistry.record(
                "closed-choice-workflow-calls", Map.of("actualCompilerCalls", 1), Set.of());
        assertThatThrownBy(() -> DesignerAcceptanceV7MeasurementRegistry.record(
                "closed-choice-workflow-calls", Map.of("actualCompilerCalls", 2), Set.of()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void candidateQualificationRequiresAtomicMeasurementsForEveryRuntimeAxis() {
        assertThatThrownBy(() -> DesignerAcceptanceV7MeasurementRegistry.record(
                "acceptance-candidate-true-tie-usage", Map.of(
                        "modelCalls", 1,
                        "candidateSessions", 1), Set.of("TRUE_TIE")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("same observation");

        var qualification = DesignerAcceptanceV7MeasurementRegistry.candidateQualification();

        assertThat(qualification.complete()).isFalse();
        assertThat(qualification.passed()).isFalse();
        assertThat(qualification.missingEvidenceIds()).containsExactlyInAnyOrder(
                "acceptance-candidate-unique-optimum-usage",
                "acceptance-candidate-true-tie-usage",
                "acceptance-candidate-non-enumerable-usage",
                "acceptance-candidate-path-safety-usage");
    }

    @Test
    void candidateQualificationSeparatesModelSessionAndSubmissionCounts() {
        for (int submissions : java.util.List.of(1, 2)) {
            DesignerAcceptanceV7MeasurementRegistry.clear();
            recordCandidateUsage("acceptance-candidate-unique-optimum-usage", 0, 0, 0,
                    "UNIQUE_OPTIMUM");
            recordCandidateUsage("acceptance-candidate-true-tie-usage", 1, 1, submissions,
                    "TRUE_TIE");
            recordCandidateUsage("acceptance-candidate-non-enumerable-usage", 0, 0, 0,
                    "NON_ENUMERABLE");
            recordCandidateUsage("acceptance-candidate-path-safety-usage", 0, 0, 0,
                    "PATH_SAFETY");

            var qualification = DesignerAcceptanceV7MeasurementRegistry.candidateQualification();

            assertThat(qualification.complete()).isTrue();
            assertThat(qualification.passed()).as("candidate submissions=%s", submissions).isTrue();
            assertThat(qualification.missingEvidenceIds()).isEmpty();
        }
    }

    @Test
    void candidateQualificationRejectsExtraCallsAndSubmissionsOutsideTheClosedBounds() {
        recordCandidateUsage("acceptance-candidate-unique-optimum-usage", 0, 0, 1,
                "UNIQUE_OPTIMUM");
        recordCandidateUsage("acceptance-candidate-true-tie-usage", 1, 1, 3,
                "TRUE_TIE");
        recordCandidateUsage("acceptance-candidate-non-enumerable-usage", 0, 1, 0,
                "NON_ENUMERABLE");
        recordCandidateUsage("acceptance-candidate-path-safety-usage", 1, 0, 0,
                "PATH_SAFETY");

        var qualification = DesignerAcceptanceV7MeasurementRegistry.candidateQualification();

        assertThat(qualification.complete()).isTrue();
        assertThat(qualification.passed()).isFalse();
    }

    private static void recordCandidateUsage(String evidenceId, int modelCalls,
                                             int candidateSessions, int candidateSubmissions,
                                             String flag) {
        DesignerAcceptanceV7MeasurementRegistry.record(evidenceId, Map.of(
                "modelCalls", modelCalls,
                "candidateSessions", candidateSessions,
                "candidateSubmissions", candidateSubmissions), Set.of(flag));
    }
}
