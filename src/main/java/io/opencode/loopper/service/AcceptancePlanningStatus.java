package io.opencode.loopper.service;

import java.util.List;

/** Bounded, human-facing projection of frozen acceptance facts and capabilities. */
public record AcceptancePlanningStatus(String state, int factCount, int scenarioCount,
                                       int automatedCount, int bothCount, int judgeCount,
                                       int unresolvedCount, List<Scenario> scenarios,
                                       List<String> issues) {
    public record Scenario(String title, String coverage, List<String> capabilities) { }
}
