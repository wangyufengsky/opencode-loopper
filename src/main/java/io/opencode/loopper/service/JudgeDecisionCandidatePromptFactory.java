package io.opencode.loopper.service;

import io.opencode.loopper.service.roles.RolePromptResources;
import io.opencode.loopper.domain.MachineCandidateKind;
import io.opencode.loopper.domain.MachineCandidateRunState;
import java.nio.charset.StandardCharsets;

/** Builds one bounded JUDGE_DECISION_V1 prompt solely from the frozen SQLite source snapshot. */
final class JudgeDecisionCandidatePromptFactory {
    private static final int MAX_PROMPT_BYTES = 128 * 1024;
    private static final int RESERVED_CONTRACT_BYTES = 4 * 1024;

    void preflight(String frozenPrompt, JudgeDecisionCompilation.EvidenceCatalog evidence,
                   JudgeDecisionCandidateCodec codec) {
        if (frozenPrompt == null || frozenPrompt.isBlank() || evidence == null || codec == null
                || frozenPrompt.getBytes(StandardCharsets.UTF_8).length
                + codec.canonical(evidence).getBytes(StandardCharsets.UTF_8).length
                + RESERVED_CONTRACT_BYTES > MAX_PROMPT_BYTES) throw tooLarge();
    }

    String internal(MachineCandidateSubmission.RunSnapshot run, String role, String frozenPrompt,
                    JudgeDecisionCompilation.EvidenceCatalog evidence, String exactSubmitTool,
                    JudgeDecisionCandidateCodec codec) {
        if (run == null || role == null || frozenPrompt == null || evidence == null
                || exactSubmitTool == null || exactSubmitTool.isBlank() || codec == null
                || run.candidateKind() != MachineCandidateKind.JUDGE_DECISION_V1
                || run.submissionChannel() != MachineCandidateSubmission.SubmissionChannel.INTERNAL_MCP
                || run.state() != MachineCandidateRunState.OPEN
                || run.maxAttempts() != MachineCandidateKind.JUDGE_DECISION_V1.maximumAttempts()
                || !JudgeDecisionCompilation.CONTRACT_VERSION.equals(run.contractVersion())) {
            throw new IllegalArgumentException("Complete open Judge candidate contract is required");
        }
        String prompt = JudgePromptPolicy.candidateEvaluationContext(frozenPrompt) + (RolePromptResources.read("prompt.v1.JudgeDecisionCandidatePromptFactory.block01.segment0")
                + String.format("%s", (Object) (exactSubmitTool))
                + RolePromptResources.read("prompt.v1.JudgeDecisionCandidatePromptFactory.block01.segment1")
                + String.format("%s", (Object) (CandidateCorrectionPolicy.prompt(run)))
                + RolePromptResources.read("prompt.v1.JudgeDecisionCandidatePromptFactory.block01.segment2")
                + String.format("%s", (Object) (role))
                + RolePromptResources.read("prompt.v1.JudgeDecisionCandidatePromptFactory.block01.segment3")
                + String.format("%s", (Object) (run.runId()))
                + "\nexpectedSubmissionRevision: "
                + String.format("%d", (Object) (run.version()))
                + "\nsourceRevision: "
                + String.format("%d", (Object) (run.sourceRevision()))
                + "\nownerVersion: "
                + String.format("%d", (Object) (run.ownerVersion()))
                + RolePromptResources.read("prompt.v1.JudgeDecisionCandidatePromptFactory.block01.segment7")
                + String.format("%s", (Object) (codec.canonical(evidence)))
                + "\n");
        if (prompt.getBytes(StandardCharsets.UTF_8).length > MAX_PROMPT_BYTES) {
            throw tooLarge();
        }
        return prompt;
    }

    private static ConflictException tooLarge() {
        return new ConflictException("JUDGE_CANDIDATE_PROMPT_TOO_LARGE",
                "Frozen Judge context cannot fit the bounded candidate prompt");
    }
}
