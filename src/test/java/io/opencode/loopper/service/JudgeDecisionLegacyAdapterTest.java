package io.opencode.loopper.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class JudgeDecisionLegacyAdapterTest {
    private final ObjectMapper json = new ObjectMapper();
    private final JudgeDecisionCompilation compilation = new DeterministicJudgeDecisionCompilation(json);
    private final JudgeDecisionLegacyAdapter adapter = new JudgeDecisionLegacyAdapter(
            new AiOutputExtractor(json), compilation);

    @Test
    void legacyJsonAndLabelsBindAllFrozenEvidenceAndMatchMcpCompilation() {
        JudgeDecisionCompilation.Input input = requirementInput();
        JudgeDecisionCompilation.Result mcp = compilation.compileCandidate(input, """
                {"contractVersion":"JUDGE_DECISION_V1","role":"REQUIREMENT","verdict":"PASS",
                 "reason":"Requirement matches","evidenceIds":["test","diff"]}
                """);
        JudgeDecisionCompilation.Result legacyJson = adapter.compile(input, """
                <LOOPPER_JUDGE_JSON>
                {"verdict":"PASS","reason":"Requirement matches","evidenceIds":["model-invented"]}
                </LOOPPER_JUDGE_JSON>
                """);
        JudgeDecisionCompilation.Result legacyLabel = adapter.compile(input, """
                判定：PASS
                理由：Requirement matches
                """);

        assertThat(legacyJson.accepted()).isTrue();
        assertThat(legacyLabel.accepted()).isTrue();
        assertThat(legacyJson.candidate().evidenceIds()).containsExactly("test", "diff");
        assertThat(legacyJson.canonicalCandidateJson()).isEqualTo(mcp.canonicalCandidateJson());
        assertThat(legacyLabel.canonicalCandidateJson()).isEqualTo(mcp.canonicalCandidateJson());
        assertThat(legacyJson.canonicalEvidenceJson()).isEqualTo(mcp.canonicalEvidenceJson());
        assertThat(legacyLabel.canonicalResultSha256()).isEqualTo(mcp.canonicalResultSha256());
    }

    @Test
    void unparseableLegacyOutputIsACompleteRetryableRejection() {
        JudgeDecisionCompilation.Result result = adapter.compile(
                requirementInput(), "I cannot decide without more context");

        assertThat(result.accepted()).isFalse();
        assertThat(result.retryable()).isTrue();
        assertThat(result.problems()).extracting(JudgeDecisionCompilation.Problem::code)
                .containsExactly("JUDGE_DECISION_VERDICT_INVALID", "JUDGE_DECISION_REASON_INVALID");
        assertThat(result.candidate()).isNull();
        assertThat(result.canonicalCandidateJson()).isNull();
        assertThat(result.deterministicReason()).isNull();
        assertThat(result.canonicalEvidenceJson()).isNull();
        assertThat(result.canonicalResultSha256()).isNull();
    }

    private static JudgeDecisionCompilation.Input requirementInput() {
        return new JudgeDecisionCompilation.Input("REQUIREMENT",
                new JudgeDecisionCompilation.EvidenceCatalog(List.of(
                        new JudgeDecisionCompilation.EvidenceItem("test", "VERIFIER",
                                "Focused tests passed", "a".repeat(64)),
                        new JudgeDecisionCompilation.EvidenceItem("diff", "DIFF",
                                "Managed diff reviewed", "b".repeat(64)))));
    }
}
