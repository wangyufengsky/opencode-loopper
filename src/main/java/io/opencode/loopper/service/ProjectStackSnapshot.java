package io.opencode.loopper.service;

import io.opencode.loopper.domain.ProjectStackProfileState;
import java.util.List;

/** Immutable, evidence-backed project stack snapshot consumed by routing and convention generation. */
public record ProjectStackSnapshot(
        String id, String projectId, ProjectStackProfileState state, String manifestFingerprint,
        List<String> technologyFamilies, List<String> technologies, List<String> evidence,
        int filesScanned, String errorCode, String errorDetail, String analyzedAt,
        List<Component> components) {

    public ProjectStackSnapshot {
        technologyFamilies = copy(technologyFamilies);
        technologies = copy(technologies);
        evidence = copy(evidence);
        components = components == null ? List.of() : List.copyOf(components);
    }

    public boolean usable() {
        return state == ProjectStackProfileState.READY || state == ProjectStackProfileState.PARTIAL;
    }

    public record Component(
            String key, String relativeRoot, List<String> technologyFamilies,
            List<String> technologies, List<String> buildTools, List<String> testFrameworks,
            List<String> manifestSources, List<String> evidence) {
        public Component {
            technologyFamilies = copy(technologyFamilies);
            technologies = copy(technologies);
            buildTools = copy(buildTools);
            testFrameworks = copy(testFrameworks);
            manifestSources = copy(manifestSources);
            evidence = copy(evidence);
        }
    }

    private static List<String> copy(List<String> values) {
        return values == null ? List.of() : List.copyOf(values);
    }
}
