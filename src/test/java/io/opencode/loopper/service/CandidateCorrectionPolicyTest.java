package io.opencode.loopper.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import io.opencode.loopper.config.LoopperProperties;
import io.opencode.loopper.domain.MachineCandidateKind;
import java.util.Map;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.api.Test;

class CandidateCorrectionPolicyTest {
    @ParameterizedTest
    @EnumSource(value=MachineCandidateKind.class, names="PACKAGE_DESIGN_V1", mode=EnumSource.Mode.EXCLUDE)
    void everyOtherRoleCanOptIntoFourSubmissionsWhileDefaultsAndLegacyStayUnlimited(MachineCandidateKind kind) {
        var properties = new LoopperProperties();
        var command = new MachineCandidateSubmission.OpenCommand("run", null, null, kind, "step", 0, 0,
                MachineCandidateSubmission.SubmissionChannel.INTERNAL_MCP, kind.name(), "gen", "remote", kind.maximumAttempts());
        assertThat(CandidateCorrectionPolicy.limit(command, properties)).isNull();
        properties.getInternalCandidate().setCorrectionLimits(Map.of(kind, 4));
        assertThat(CandidateCorrectionPolicy.limit(command, properties)).isEqualTo(4);
        var legacy = new MachineCandidateSubmission.OpenCommand("legacy", null, null, kind, "step", 0, 0,
                MachineCandidateSubmission.SubmissionChannel.IN_PROCESS_LEGACY, kind.name(), "gen", "remote", kind.maximumAttempts());
        assertThat(CandidateCorrectionPolicy.limit(legacy, properties)).isNull();
    }

    @Test
    void configurationRejectsInvalidLimitsAndPromptExplainsOnlyItsFrozenBudget() {
        var config = new LoopperProperties.InternalCandidate();
        assertThatThrownBy(() -> config.setCorrectionLimits(Map.of(MachineCandidateKind.JUDGE_DECISION_V1, 1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(CandidateCorrectionPolicy.prompt(4)).contains("4 total submissions", "comparisonComplete=false", "submissionRevision")
                .doesNotContain("no count limit");
        assertThat(CandidateCorrectionPolicy.prompt((Integer) null)).contains("no count limit");
    }
}
