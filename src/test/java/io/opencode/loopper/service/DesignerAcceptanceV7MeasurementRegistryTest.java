package io.opencode.loopper.service;

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
}
