package io.opencode.loopper.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class LoopperPropertiesTest {
    @Test
    void qualifiedRollingPackageCandidateDefaultsOnAndCanBeDisabledExplicitly() {
        LoopperProperties properties = new LoopperProperties();

        assertThat(properties.getInternalCandidate().isRollingPackagePlanV1Enabled()).isTrue();

        properties.getInternalCandidate().setRollingPackagePlanV1Enabled(false);
        assertThat(properties.getInternalCandidate().isRollingPackagePlanV1Enabled()).isFalse();
    }

    @Test
    void packageDesignCandidateDefaultsOnAndCanBeDisabledExplicitly() {
        LoopperProperties properties = new LoopperProperties();

        assertThat(properties.getInternalCandidate().isPackageDesignV1Enabled()).isTrue();

        properties.getInternalCandidate().setPackageDesignV1Enabled(false);
        assertThat(properties.getInternalCandidate().isPackageDesignV1Enabled()).isFalse();
    }

    @Test
    void qualifiedAcceptanceCandidateDefaultsOnAndCanBeDisabledExplicitly() {
        LoopperProperties properties = new LoopperProperties();

        assertThat(properties.getInternalCandidate().isAcceptanceClosedChoiceV7Enabled()).isTrue();

        properties.getInternalCandidate().setAcceptanceClosedChoiceV7Enabled(false);
        assertThat(properties.getInternalCandidate().isAcceptanceClosedChoiceV7Enabled()).isFalse();
    }
}
