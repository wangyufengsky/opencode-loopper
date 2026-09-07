package io.opencode.loopper.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class LoopperPropertiesTest {
    @Test
    void behaviorQualificationGateDoesNotDisableExistingV2() throws Exception {
        var properties = new LoopperProperties();
        assertThat(properties.getInternalCandidate().isPackageDesignV2Enabled()).isTrue();
        assertThat(properties.getInternalCandidate().isPackageBehaviorEnabled()).isFalse();
        properties.getInternalCandidate().setPackageBehaviorEnabled(true);
        assertThat(properties.getInternalCandidate().isPackageBehaviorEnabled()).isTrue();
        assertThat(Files.readString(Path.of("src/main/resources/application.yml")))
                .contains("package-behavior-enabled: ${LOOPPER_PACKAGE_BEHAVIOR_ENABLED:false}");
    }

    @Test
    void packageDesignV2DefaultsOnWithAnExplicitNewRunRollback() throws Exception {
        var properties = new LoopperProperties();
        assertThat(properties.getInternalCandidate().isPackageDesignV2Enabled()).isTrue();
        properties.getInternalCandidate().setPackageDesignV2Enabled(false);
        assertThat(properties.getInternalCandidate().isPackageDesignV2Enabled()).isFalse();
        assertThat(Files.readString(Path.of("src/main/resources/application.yml")))
                .contains("package-design-v2-enabled: ${LOOPPER_PACKAGE_DESIGN_V2_ENABLED:true}");
    }

    @Test
    void qualifiedProjectConventionCandidateDefaultsOnAndCanBeDisabledExplicitly() throws Exception {
        LoopperProperties properties = new LoopperProperties();

        assertThat(properties.getInternalCandidate().isProjectConventionV1Enabled()).isTrue();

        properties.getInternalCandidate().setProjectConventionV1Enabled(false);
        assertThat(properties.getInternalCandidate().isProjectConventionV1Enabled()).isFalse();
        assertThat(Files.readString(Path.of(System.getProperty("user.dir"),
                "src/main/resources/application.yml")))
                .contains("project-convention-v1-enabled: "
                        + "${LOOPPER_PROJECT_CONVENTION_CANDIDATE_V1_ENABLED:true}");
    }

    @Test
    void qualifiedJudgeDecisionCandidateDefaultsOnAndCanBeDisabledExplicitly() throws Exception {
        LoopperProperties properties = new LoopperProperties();

        assertThat(properties.getInternalCandidate().isJudgeDecisionV1Enabled()).isTrue();

        properties.getInternalCandidate().setJudgeDecisionV1Enabled(false);
        assertThat(properties.getInternalCandidate().isJudgeDecisionV1Enabled()).isFalse();
        assertThat(Files.readString(Path.of(System.getProperty("user.dir"),
                "src/main/resources/application.yml")))
                .contains("judge-decision-v1-enabled: "
                        + "${LOOPPER_JUDGE_DECISION_CANDIDATE_V1_ENABLED:true}");
    }

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
