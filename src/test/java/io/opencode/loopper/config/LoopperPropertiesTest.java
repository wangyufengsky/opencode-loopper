package io.opencode.loopper.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class LoopperPropertiesTest {
    @Test
    void qualifiedReviewerReportCandidateDefaultsOnAndCanBeDisabledExplicitly() throws Exception {
        LoopperProperties properties = new LoopperProperties();

        assertThat(properties.getInternalCandidate().isReviewerReportV1Enabled()).isTrue();

        properties.getInternalCandidate().setReviewerReportV1Enabled(false);
        assertThat(properties.getInternalCandidate().isReviewerReportV1Enabled()).isFalse();
        assertThat(Files.readString(Path.of(System.getProperty("user.dir"),
                "src/main/resources/application.yml")))
                .contains("reviewer-report-v1-enabled: "
                        + "${LOOPPER_REVIEWER_REPORT_CANDIDATE_V1_ENABLED:true}");
    }

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
