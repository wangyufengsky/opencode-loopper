package io.opencode.loopper.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class LoopperPropertiesTest {
    @Test
    void packageDesignCandidateDefaultsOnAndCanBeDisabledExplicitly() {
        LoopperProperties properties = new LoopperProperties();

        assertThat(properties.getInternalCandidate().isPackageDesignV1Enabled()).isTrue();

        properties.getInternalCandidate().setPackageDesignV1Enabled(false);
        assertThat(properties.getInternalCandidate().isPackageDesignV1Enabled()).isFalse();
    }
}
