package io.opencode.loopper.service;

import static org.assertj.core.api.Assertions.assertThat;

import io.opencode.loopper.domain.MachineCandidateKind;
import java.util.Map;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class CandidateShapeValidatorTest {
    private static final Map<MachineCandidateKind, Integer> REQUIRED_ROOT_FIELDS = Map.ofEntries(
            Map.entry(MachineCandidateKind.DECOMPOSITION_PLAN_V2, 7),
            Map.entry(MachineCandidateKind.ACCEPTANCE_CLOSED_CHOICE_V7, 2),
            Map.entry(MachineCandidateKind.PACKAGE_DESIGN_V1, 8),
            Map.entry(MachineCandidateKind.ROLLING_PACKAGE_PLAN_V1, 1),
            Map.entry(MachineCandidateKind.REVIEWER_REPORT_V1, 4),
            Map.entry(MachineCandidateKind.PROJECT_CONVENTION_V1, 4),
            Map.entry(MachineCandidateKind.JUDGE_DECISION_V1, 5),
            Map.entry(MachineCandidateKind.DOCUMENT_REQUIREMENTS_V1, 2),
            Map.entry(MachineCandidateKind.DOCUMENT_REQUIREMENT_REVIEW_V1, 4),
            Map.entry(MachineCandidateKind.REQUIREMENT_CODE_ASSESSMENT_V1, 4),
            Map.entry(MachineCandidateKind.REQUIREMENT_ASSESSMENT_REVIEW_V1, 5),
            Map.entry(MachineCandidateKind.DOCUMENT_CODE_ASSESSMENT_V2, 5),
            Map.entry(MachineCandidateKind.DOCUMENT_CODE_REVIEW_V2, 6));

    @Test
    void conventionReportsTheExactOversizedArrayBeforeTheModelHasToGuess() {
        var json = JsonMapper.builder().build();
        String candidate = json.writeValueAsString(Map.of("contractVersion", "PROJECT_CONVENTION_V1",
                "componentKeys", java.util.Collections.nCopies(65, "component"), "commandIds", java.util.List.of(),
                "pathIds", java.util.List.of()));
        assertThat(CandidateShapeValidator.validate(json, MachineCandidateKind.PROJECT_CONVENTION_V1, candidate).problems())
                .singleElement().satisfies(problem -> {
                    assertThat(problem.pointer()).isEqualTo("/componentKeys");
                    assertThat(problem.expected()).contains("64");
                    assertThat(problem.actual()).isEqualTo("65");
                });
    }

    @Test
    void chineseJudgeReasonUsesActualUtf8BytesAndAllowsCompilerWhitespaceNormalization() {
        var json = JsonMapper.builder().build();
        var candidate = new java.util.LinkedHashMap<String, Object>(Map.of("contractVersion", "JUDGE_DECISION_V1",
                "role", "REQUIREMENT", "verdict", "REVISE", "reason", "中".repeat(2000), "evidenceIds", java.util.List.of("test")));
        var result = CandidateShapeValidator.validate(json, MachineCandidateKind.JUDGE_DECISION_V1, json.writeValueAsString(candidate));
        assertThat(result.problems()).singleElement().satisfies(problem -> {
            assertThat(problem.pointer()).isEqualTo("/reason");
            assertThat(problem.expected()).contains("4000 UTF-8 bytes");
            assertThat(problem.actual()).isEqualTo("6000 UTF-8 bytes");
            assertThat(problem.repairHint()).contains("verdict");
        });
        candidate.put("reason", " ".repeat(5000) + "需修正");
        assertThat(CandidateShapeValidator.validate(json, MachineCandidateKind.JUDGE_DECISION_V1,
                json.writeValueAsString(candidate)).problems()).isEmpty();
    }

    @Test
    void reviewerLimitsRemainCompatibleWithEmptyFindingsAndReportAllIndependentTextErrors() {
        var json = JsonMapper.builder().build();
        String candidate = json.writeValueAsString(Map.of("title", "中".repeat(100), "summary", "中".repeat(3000),
                "findings", java.util.List.of(), "limitations", java.util.Collections.nCopies(33, "限制")));
        assertThat(CandidateShapeValidator.validate(json, MachineCandidateKind.REVIEWER_REPORT_V1, candidate).problems())
                .extracting(MachineCandidateSubmission.Problem::pointer).containsExactlyInAnyOrder("/title", "/summary", "/limitations");
        assertThat(CandidateShapeValidator.validate(json, MachineCandidateKind.REVIEWER_REPORT_V1,
                "{\"title\":\"审查\",\"summary\":\"无已确认问题\",\"findings\":[],\"limitations\":[]}").problems()).isEmpty();
    }

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
