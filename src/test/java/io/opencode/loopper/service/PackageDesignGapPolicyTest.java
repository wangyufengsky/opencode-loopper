package io.opencode.loopper.service;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class PackageDesignGapPolicyTest {
    @Test void noEvidenceDoesNotTurnMissingExceptionClaimIntoAUserDecision() {
        var problem = problem("MISSING_EXCEPTION_SEMANTICS", "/gapCodes/0", PackageDesignCompilation.ProblemClass.HUMAN_REQUIRED);
        var original = result(problem);
        var corrected = PackageDesignGapPolicy.apply(original);
        assertThat(original.outcome()).isEqualTo(PackageDesignCompilation.Outcome.NEEDS_INPUT);
        assertThat(corrected.retryable()).isTrue();
        assertThat(corrected.problems()).singleElement().satisfies(value -> {
            assertThat(value.code()).isEqualTo("PACKAGE_GAP_UNCONFIRMED");
            assertThat(value.pointer()).isEqualTo("/gapCodes/0");
            assertThat(value.fallbackEligible()).isFalse();
            assertThat(value.repairHint()).contains("不能编造", "CLARIFY_CLAIM");
        });
    }

    @Test void provenConflictAndAuthorityFailuresAreNeverReclassifiedByCodeAlone() {
        for (String pointer : List.of("/requirement", "/candidate/allowedPaths", "/scenarios/0")) {
            var original = result(problem("REQUIRED_MUTATION_PATH_FORBIDDEN", pointer, PackageDesignCompilation.ProblemClass.SECURITY));
            assertThat(PackageDesignGapPolicy.apply(original)).isEqualTo(original);
        }
        var mixed = new PackageDesignCompilation.Result(PackageDesignCompilation.Outcome.NEEDS_INPUT, null, null, null, null,
                List.of(problem("MISSING_SCOPE", "/gapCodes/0", PackageDesignCompilation.ProblemClass.HUMAN_REQUIRED),
                        problem("SECURITY", "/allowedPaths", PackageDesignCompilation.ProblemClass.SECURITY)));
        assertThat(PackageDesignGapPolicy.apply(mixed).retryable()).isFalse();
    }

    @Test void existingRunStrategySurvivesBothDirectionsOfFeatureToggle() {
        assertThat(PackageDesignGapPolicy.workflowStep("PACKAGE_DESIGN_V1", true)).isEqualTo("PACKAGE_DESIGN_V1");
        assertThat(PackageDesignGapPolicy.workflowStep(PackageDesignGapPolicy.WORKFLOW_STEP, false)).isEqualTo(PackageDesignGapPolicy.WORKFLOW_STEP);
        assertThat(PackageDesignGapPolicy.workflowStep(null, false)).isEqualTo("PACKAGE_DESIGN_V1");
        assertThat(PackageDesignGapPolicy.workflowStep(null, true)).isEqualTo(PackageDesignGapPolicy.WORKFLOW_STEP);
    }

    @Test void diagnosticIdentityAndUtf8BoundingRemainAvailableAfterClassification() {
        var problem = PackageDesignGapPolicy.apply(result(problem("MISSING_SCOPE", "/gapCodes/0",
                PackageDesignCompilation.ProblemClass.HUMAN_REQUIRED))).problems().getFirst().submissionProblem();
        var enriched = CandidateDiagnosticEnricher.enrich(new ObjectMapper(), "{\"gapCodes\":[\"MISSING_SCOPE\"]}", List.of(problem));
        var bounded = CandidateDiagnosticEnricher.bound(enriched);
        assertThat(bounded.complete()).isTrue();
        assertThat(bounded.problems()).singleElement().satisfies(value -> {
            assertThat(value.expected()).isNotBlank();
            assertThat(value.actual()).contains("MISSING_SCOPE");
            assertThat(value.expected()).contains("冻结需求");
        });
    }

    private PackageDesignCompilation.Problem problem(String code, String pointer, PackageDesignCompilation.ProblemClass kind) {
        return new PackageDesignCompilation.Problem(code, pointer, "detail", List.of(), kind, false);
    }
    private PackageDesignCompilation.Result result(PackageDesignCompilation.Problem problem) {
        return new PackageDesignCompilation.Result(PackageDesignCompilation.Outcome.NEEDS_INPUT, null, null, null, null, List.of(problem));
    }
}
