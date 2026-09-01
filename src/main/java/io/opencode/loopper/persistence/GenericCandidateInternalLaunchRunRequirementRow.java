package io.opencode.loopper.persistence;

import org.apache.ibatis.annotations.AutomapConstructor;

/** Deferred-FK half of the V57 run/launch settlement certificate. */
public record GenericCandidateInternalLaunchRunRequirementRow(
        String candidateRunId, String launchId, String createdAt) {
    @AutomapConstructor public GenericCandidateInternalLaunchRunRequirementRow { }
}
