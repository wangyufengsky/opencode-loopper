package io.opencode.loopper.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opencode.loopper.domain.MachineCandidateKind;
import io.opencode.loopper.domain.MachineCandidateRunState;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class JudgeDecisionCandidatePromptFactoryTest {
    @Test
    void publishesExactRoleEvidenceIdsToolAndCurrentSubmissionRevision() {
        var evidence = new JudgeDecisionCompilation.EvidenceCatalog(List.of(
                new JudgeDecisionCompilation.EvidenceItem(
                        "verification-v2", "VERIFICATION_SUMMARY", "All deterministic checks passed",
                        "a".repeat(64)),
                new JudgeDecisionCompilation.EvidenceItem(
                        "task-diff", "GIT_DIFF", "Persisted task diff",
                        "b".repeat(64))));
        var run = new MachineCandidateSubmission.RunSnapshot(
                "run-1", MachineCandidateSubmission.CandidateScope.task("task-1"),
                MachineCandidateSubmission.CandidateOwnerRef.judgeRun("judge-1"),
                MachineCandidateKind.JUDGE_DECISION_V1, "JUDGE_DECISION_V1", 2, 4,
                MachineCandidateSubmission.SubmissionChannel.INTERNAL_MCP,
                "JUDGE_DECISION_V1", "generation-1", "remote-1",
                MachineCandidateRunState.OPEN, 2, 0, null, 7, null);

        String prompt = new JudgeDecisionCandidatePromptFactory().internal(
                run, "RISK", "Frozen evaluation context", evidence,
                "loopper_internal_submit_candidate", new JudgeDecisionCandidateCodec(new ObjectMapper()));

        assertThat(prompt)
                .contains("Frozen evaluation context", "JUDGE_DECISION_V1", "RISK",
                        "verification-v2", "task-diff", "loopper_internal_submit_candidate")
                .contains("expectedSubmissionRevision: 7", "returned submissionRevision")
                .contains("one line", "no CR, LF, or TAB")
                .contains("Do not return the candidate as final assistant text", "fallbackAllowed: false")
                .doesNotContain("fallbackAllowed: true");
    }

    @Test
    void rejectsOversizedFrozenContextBeforeRemoteLaunchPreparation() {
        var codec = new JudgeDecisionCandidateCodec(new ObjectMapper());
        var evidence = new JudgeDecisionCompilation.EvidenceCatalog(List.of(
                new JudgeDecisionCompilation.EvidenceItem(
                        "test", "TEST", "test evidence", "a".repeat(64))));

        assertThatThrownBy(() -> new JudgeDecisionCandidatePromptFactory().preflight(
                "x".repeat(128 * 1024), evidence, codec))
                .isInstanceOfSatisfying(ConflictException.class, failure ->
                        assertThat(failure.code()).isEqualTo("JUDGE_CANDIDATE_PROMPT_TOO_LARGE"));
    }
}
