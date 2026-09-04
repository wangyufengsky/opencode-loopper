package io.opencode.loopper.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class JudgeDecisionCompilationTest {
    private final JudgeDecisionCompilation compilation =
            new DeterministicJudgeDecisionCompilation(new ObjectMapper());

    @Test
    void compilesMcpCandidateAgainstFrozenEvidenceInCanonicalCatalogOrder() {
        JudgeDecisionCompilation.Result result = compilation.compileCandidate(requirementInput(), """
                {
                  "contractVersion": "JUDGE_DECISION_V1",
                  "role": "REQUIREMENT",
                  "verdict": "PASS",
                  "reason": "  Requirement matches  ",
                  "evidenceIds": ["diff", "test"]
                }
                """);

        assertThat(result.accepted()).isTrue();
        assertThat(result.canonicalCandidateJson()).isEqualTo("""
                {"contractVersion":"JUDGE_DECISION_V1","role":"REQUIREMENT","verdict":"PASS","reason":"Requirement matches","evidenceIds":["test","diff"]}""");
        assertThat(result.deterministicReason()).isEqualTo("""
                Requirement matches

                已验证证据：
                - [VERIFIER] Focused tests passed
                - [DIFF] Managed diff reviewed""");
        assertThat(result.canonicalEvidenceJson()).isEqualTo("""
                [{"id":"test","kind":"VERIFIER","label":"Focused tests passed","sourceSha256":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"},{"id":"diff","kind":"DIFF","label":"Managed diff reviewed","sourceSha256":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"}]""");
        assertThat(result.canonicalResultSha256()).hasSize(64);
        assertThat(result.problems()).isEmpty();
    }

    @Test
    void reasonControlCharacterIsANonRetryableSecurityFailureWithNoPartialResult() {
        JudgeDecisionCompilation.Result result = compilation.compileCandidate(requirementInput(), """
                {"contractVersion":"JUDGE_DECISION_V1","role":"REQUIREMENT","verdict":"PASS",
                 "reason":"unsafe\\u0007reason","evidenceIds":["test"]}
                """);

        assertThat(result.accepted()).isFalse();
        assertThat(result.retryable()).isFalse();
        assertThat(result.problems()).singleElement().satisfies(problem -> {
            assertThat(problem.code()).isEqualTo("JUDGE_DECISION_REASON_CONTROL_INVALID");
            assertThat(problem.problemClass()).isEqualTo(JudgeDecisionCompilation.ProblemClass.SECURITY);
        });
        assertNoCompiledResult(result);
    }

    @Test
    void invalidFrozenEvidenceHashStopsBeforeCandidateEvidenceValidation() {
        JudgeDecisionCompilation.Input invalid = new JudgeDecisionCompilation.Input("REQUIREMENT",
                new JudgeDecisionCompilation.EvidenceCatalog(List.of(
                        new JudgeDecisionCompilation.EvidenceItem("test", "VERIFIER",
                                "Focused tests passed", "not-a-sha"))));

        JudgeDecisionCompilation.Result result = compilation.compileCandidate(invalid, """
                {"contractVersion":"JUDGE_DECISION_V1","role":"REQUIREMENT","verdict":"PASS",
                 "reason":"Requirement matches","evidenceIds":["test"]}
                """);

        assertThat(result.retryable()).isFalse();
        assertThat(result.problems()).extracting(JudgeDecisionCompilation.Problem::code)
                .containsExactly("JUDGE_DECISION_EVIDENCE_CATALOG_INVALID");
        assertNoCompiledResult(result);
    }

    @Test
    void strictCodecRejectsAuthorityFieldsDuplicateKeysAndTrailingObjects() {
        JudgeDecisionCompilation.Result authority = compilation.compileCandidate(requirementInput(), """
                {"contractVersion":"JUDGE_DECISION_V1","role":"REQUIREMENT","verdict":"PASS",
                 "reason":"ok","evidenceIds":["test"],"taskId":"model-owned"}
                """);
        JudgeDecisionCompilation.Result duplicate = compilation.compileCandidate(requirementInput(), """
                {"contractVersion":"JUDGE_DECISION_V1","role":"REQUIREMENT","role":"RISK",
                 "verdict":"PASS","reason":"ok","evidenceIds":["test"]}
                """);
        JudgeDecisionCompilation.Result trailing = compilation.compileCandidate(requirementInput(), """
                {"contractVersion":"JUDGE_DECISION_V1","role":"REQUIREMENT","verdict":"PASS",
                 "reason":"ok","evidenceIds":["test"]} {"role":"RISK"}
                """);

        assertThat(authority.retryable()).isFalse();
        assertThat(authority.problems()).extracting(JudgeDecisionCompilation.Problem::code)
                .containsExactly("JUDGE_DECISION_AUTHORITY_FIELD_FORBIDDEN");
        assertThat(duplicate.retryable()).isTrue();
        assertThat(trailing.retryable()).isTrue();
        assertThat(duplicate.problems()).extracting(JudgeDecisionCompilation.Problem::code)
                .containsExactly("JUDGE_DECISION_CANDIDATE_JSON_INVALID");
        assertThat(trailing.problems()).extracting(JudgeDecisionCompilation.Problem::code)
                .containsExactly("JUDGE_DECISION_CANDIDATE_JSON_INVALID");
        assertNoCompiledResult(authority);
        assertNoCompiledResult(duplicate);
        assertNoCompiledResult(trailing);
    }

    @Test
    void frozenRoleOrContractDriftIsNeverRetryable() {
        JudgeDecisionCompilation.Result role = compilation.compileCandidate(requirementInput(), """
                {"contractVersion":"JUDGE_DECISION_V1","role":"RISK","verdict":"PASS",
                 "reason":"ok","evidenceIds":["test"]}
                """);
        JudgeDecisionCompilation.Result contract = compilation.compileCandidate(requirementInput(), """
                {"contractVersion":"JUDGE_DECISION_V2","role":"REQUIREMENT","verdict":"PASS",
                 "reason":"ok","evidenceIds":["test"]}
                """);

        assertThat(role.retryable()).isFalse();
        assertThat(role.problems()).extracting(JudgeDecisionCompilation.Problem::code)
                .containsExactly("JUDGE_DECISION_ROLE_MISMATCH");
        assertThat(contract.retryable()).isFalse();
        assertThat(contract.problems()).extracting(JudgeDecisionCompilation.Problem::code)
                .containsExactly("JUDGE_DECISION_CONTRACT_VERSION_INVALID");
        assertNoCompiledResult(role);
        assertNoCompiledResult(contract);
    }

    @Test
    void emptyDuplicateOrUnknownEvidenceSelectionIsRetryableButNeverPartiallyAccepted() {
        JudgeDecisionCompilation.Result empty = candidateWithEvidenceIds(List.of());
        JudgeDecisionCompilation.Result duplicate = candidateWithEvidenceIds(List.of("test", "test"));
        JudgeDecisionCompilation.Result unknown = candidateWithEvidenceIds(List.of("not-frozen"));

        assertThat(empty.retryable()).isTrue();
        assertThat(empty.problems()).extracting(JudgeDecisionCompilation.Problem::code)
                .containsExactly("JUDGE_DECISION_EVIDENCE_REQUIRED");
        assertThat(duplicate.retryable()).isTrue();
        assertThat(duplicate.problems()).singleElement().satisfies(problem -> {
            assertThat(problem.code()).isEqualTo("JUDGE_DECISION_EVIDENCE_INVALID");
            assertThat(problem.pointer()).isEqualTo("/evidenceIds/1");
        });
        assertThat(unknown.retryable()).isTrue();
        assertThat(unknown.problems()).singleElement().satisfies(problem -> {
            assertThat(problem.code()).isEqualTo("JUDGE_DECISION_EVIDENCE_INVALID");
            assertThat(problem.pointer()).isEqualTo("/evidenceIds/0");
        });
        assertNoCompiledResult(empty);
        assertNoCompiledResult(duplicate);
        assertNoCompiledResult(unknown);
    }

    @Test
    void reasonUsesUtf8ByteLimitAndVerdictRemainsAClosedMechanicalChoice() {
        JudgeDecisionCompilation.Result reason = compilation.compileCandidate(requirementInput(), """
                {"contractVersion":"JUDGE_DECISION_V1","role":"REQUIREMENT","verdict":"PASS",
                 "reason":"%s","evidenceIds":["test"]}
                """.formatted("验".repeat(1_334)));
        JudgeDecisionCompilation.Result verdict = compilation.compileCandidate(requirementInput(), """
                {"contractVersion":"JUDGE_DECISION_V1","role":"REQUIREMENT","verdict":"pass",
                 "reason":"ok","evidenceIds":["test"]}
                """);

        assertThat(reason.retryable()).isTrue();
        assertThat(reason.problems()).extracting(JudgeDecisionCompilation.Problem::code)
                .containsExactly("JUDGE_DECISION_REASON_INVALID");
        assertThat(verdict.retryable()).isTrue();
        assertThat(verdict.problems()).extracting(JudgeDecisionCompilation.Problem::code)
                .containsExactly("JUDGE_DECISION_VERDICT_INVALID");
        assertNoCompiledResult(reason);
        assertNoCompiledResult(verdict);
    }

    private JudgeDecisionCompilation.Result candidateWithEvidenceIds(List<String> evidenceIds) {
        String ids = evidenceIds.stream().map(id -> "\"" + id + "\"")
                .collect(java.util.stream.Collectors.joining(","));
        return compilation.compileCandidate(requirementInput(), """
                {"contractVersion":"JUDGE_DECISION_V1","role":"REQUIREMENT","verdict":"PASS",
                 "reason":"ok","evidenceIds":[%s]}
                """.formatted(ids));
    }

    private static void assertNoCompiledResult(JudgeDecisionCompilation.Result result) {
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
