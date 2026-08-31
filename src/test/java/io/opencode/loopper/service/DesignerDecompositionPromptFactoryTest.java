package io.opencode.loopper.service;

import static org.assertj.core.api.Assertions.assertThat;

import io.opencode.loopper.persistence.DesignRequirementRevisionRow;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class DesignerDecompositionPromptFactoryTest {
    @Test
    void candidatePromptMakesTheSubmissionToolAndCorrectionLoopAuthoritative() {
        DesignerDecompositionPromptFactory prompts =
                new DesignerDecompositionPromptFactory(new ObjectMapper(), null, null);

        String prompt = prompts.candidate(revision(), "/managed/project", "run-42", 7,
                "DECOMPOSITION_PLAN_V2", "loopper_internal_submit_candidate");

        assertThat(prompt).contains(
                "run-42", "expectedSubmissionRevision: 7", "DECOMPOSITION_PLAN_V2",
                "loopper_internal_submit_candidate", "read, glob, and grep",
                "exactly one call", "REJECTED", "all returned problems", "same Session",
                "ACCEPTED", "WAITING_INPUT", "stop", "final text is non-authoritative");
        assertThat(prompt).contains("candidate containing one complete compact JSON");
        assertThat(prompt).doesNotContain("candidateJson", "write files", "execute commands");
    }

    private DesignRequirementRevisionRow revision() {
        return new DesignRequirementRevisionRow("rev", "session", 2, "message", "complete requirement",
                "[{\"id\":\"RQ-1\",\"text\":\"observable result\"}]", 0,
                "ACTIVE", 0, 8, "now", "now", 0);
    }
}
