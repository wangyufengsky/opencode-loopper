package io.opencode.loopper.service;

import static org.assertj.core.api.Assertions.assertThat;

import io.opencode.loopper.domain.MachineCandidateKind;
import java.util.Map;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class CandidateShapeValidatorTest {
    private static final Map<MachineCandidateKind, Integer> REQUIRED_ROOT_FIELDS = Map.of(
            MachineCandidateKind.DECOMPOSITION_PLAN_V2, 7,
            MachineCandidateKind.ACCEPTANCE_CLOSED_CHOICE_V7, 2,
            MachineCandidateKind.PACKAGE_DESIGN_V1, 8,
            MachineCandidateKind.ROLLING_PACKAGE_PLAN_V1, 1,
            MachineCandidateKind.REVIEWER_REPORT_V1, 4,
            MachineCandidateKind.PROJECT_CONVENTION_V1, 4,
            MachineCandidateKind.JUDGE_DECISION_V1, 5);

    @ParameterizedTest
    @EnumSource(MachineCandidateKind.class)
    void reportsEveryMissingRequiredRootFieldForEveryRoleInOneCompletePass(MachineCandidateKind kind) {
        CandidateShapeValidator.Result result = CandidateShapeValidator.validate(
                JsonMapper.builder().build(), kind, "{}");

        assertThat(result.complete()).isTrue();
        assertThat(result.problems()).hasSize(REQUIRED_ROOT_FIELDS.get(kind));
        assertThat(result.problems()).allSatisfy(problem -> {
            assertThat(problem.code()).isEqualTo("CANDIDATE_FIELD_REQUIRED");
            assertThat(problem.pointer()).startsWith("/");
            assertThat(problem.parameter()).isEqualTo("candidate");
            assertThat(problem.category()).isEqualTo(MachineCandidateSubmission.ProblemCategory.SHAPE);
            assertThat(problem.expected()).contains("required field");
            assertThat(problem.actual()).isEqualTo("missing");
        });
    }

    @ParameterizedTest
    @EnumSource(MachineCandidateKind.class)
    void reportsWrongRootTypePreciselyWithoutGuessingNestedFailures(MachineCandidateKind kind) {
        CandidateShapeValidator.Result result = CandidateShapeValidator.validate(
                JsonMapper.builder().build(), kind, "[]");

        assertThat(result.complete()).isTrue();
        assertThat(result.problems()).singleElement().satisfies(problem -> {
            assertThat(problem.code()).isEqualTo("CANDIDATE_TYPE_INVALID");
            assertThat(problem.pointer()).isEqualTo("/candidate");
            assertThat(problem.expected()).isEqualTo("object");
            assertThat(problem.actual()).isEqualTo("array");
        });
    }

    @Test
    void reportsIndependentNestedTypeValueShapeAndAuthorityProblemsInOnePass() {
        CandidateShapeValidator.Result result = CandidateShapeValidator.validate(
                JsonMapper.builder().build(), MachineCandidateKind.JUDGE_DECISION_V1, """
                        {"contractVersion":"WRONG","role":7,"verdict":"MAYBE","reason":"",
                         "evidenceIds":[3],"note":"remove me","serverCommand":"rm"}
                        """);

        assertThat(result.complete()).isTrue();
        assertThat(result.problems()).extracting(MachineCandidateSubmission.Problem::pointer)
                .containsExactlyInAnyOrder("/contractVersion", "/role", "/verdict", "/reason",
                        "/evidenceIds/0", "/note", "/serverCommand");
        assertThat(result.problems()).extracting(MachineCandidateSubmission.Problem::category)
                .contains(MachineCandidateSubmission.ProblemCategory.TYPE,
                        MachineCandidateSubmission.ProblemCategory.VALUE,
                        MachineCandidateSubmission.ProblemCategory.SHAPE,
                        MachineCandidateSubmission.ProblemCategory.AUTHORITY);
        assertThat(result.problems()).filteredOn(problem -> problem.pointer().equals("/note"))
                .singleElement().satisfies(problem ->
                        assertThat(problem.category()).isEqualTo(MachineCandidateSubmission.ProblemCategory.SHAPE));
        assertThat(result.problems()).filteredOn(problem -> problem.pointer().equals("/serverCommand"))
                .singleElement().satisfies(problem ->
                        assertThat(problem.category()).isEqualTo(MachineCandidateSubmission.ProblemCategory.AUTHORITY));
    }
}
