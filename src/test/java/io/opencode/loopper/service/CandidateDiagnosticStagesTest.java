package io.opencode.loopper.service;

import static org.assertj.core.api.Assertions.assertThat;
import io.opencode.loopper.domain.MachineCandidateKind;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class CandidateDiagnosticStagesTest {
    private final ObjectMapper json = new ObjectMapper();

    @ParameterizedTest
    @EnumSource(MachineCandidateKind.class)
    void shapeErrorsNeverHideAnExistingRoleSecurityOrHumanBoundary(MachineCandidateKind kind) {
        var decision = CandidateDiagnosticStages.evaluate(json, kind, "{}", () ->
                CandidatePolicy.Decision.rejected(false, List.of(new MachineCandidateSubmission.Problem(
                        "FROZEN_INPUT_INVALID", "/input", "Frozen input requires owner intervention"))));
        assertThat(decision.retryable()).isFalse();
        assertThat(decision.problems()).extracting(MachineCandidateSubmission.Problem::code)
                .containsExactly("FROZEN_INPUT_INVALID");
    }

    @ParameterizedTest
    @EnumSource(MachineCandidateKind.class)
    void safeShapeFeedbackSuppressesDependentMechanicalFailures(MachineCandidateKind kind) {
        var decision = CandidateDiagnosticStages.evaluate(json, kind, "{}", () ->
                CandidatePolicy.Decision.rejected(true, List.of(new MachineCandidateSubmission.Problem(
                        "DOWNSTREAM_MISSING_COVERAGE", "/candidate", "Missing coverage"))));
        assertThat(decision.retryable()).isTrue();
        assertThat(decision.problems()).allMatch(problem -> problem.code().equals("CANDIDATE_FIELD_REQUIRED"));
    }

    @Test
    void correctlyFormattedNegativeJudgeVerdictIsAcceptedWithoutChangingItsMeaning() {
        var compiler = new DeterministicJudgeDecisionCompilation(json);
        var input = new JudgeDecisionCompilation.Input("RISK", new JudgeDecisionCompilation.EvidenceCatalog(List.of(
                new JudgeDecisionCompilation.EvidenceItem("risk", "DIFF", "Unresolved risk", "a".repeat(64)))));
        String candidate = "{\"contractVersion\":\"JUDGE_DECISION_V1\",\"role\":\"RISK\",\"verdict\":\"BLOCKED\","
                + "\"reason\":\"风险仍未解决\",\"evidenceIds\":[\"risk\"]}";
        var result = CandidateDiagnosticStages.evaluate(json, MachineCandidateKind.JUDGE_DECISION_V1, candidate,
                () -> CandidatePolicy.Decision.accepted(compiler.compileCandidate(input, candidate).canonicalCandidateJson()));
        assertThat(result.accepted()).isTrue();
        assertThat(result.canonicalCandidateJson()).contains("BLOCKED");
    }
}
