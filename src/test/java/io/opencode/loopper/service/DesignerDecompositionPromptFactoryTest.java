package io.opencode.loopper.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import io.opencode.loopper.persistence.DesignRequirementRevisionRow;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class DesignerDecompositionPromptFactoryTest {
    @Test
    void candidatePromptMakesTheSubmissionToolAndCorrectionLoopAuthoritative() {
        TaskProfileService profiles = mock(TaskProfileService.class);
        TaskProfileService.View profile = mock(TaskProfileService.View.class);
        when(profiles.current("session")).thenReturn(profile);
        when(profile.rolePackId()).thenReturn("software-python");
        when(profile.rolePackVersion()).thenReturn(RolePackRegistry.VERSION);
        when(profile.workflowTemplate()).thenReturn(io.opencode.loopper.domain.WorkflowTemplate.FULL_PACKAGE_DESIGN);
        DesignerDecompositionPromptFactory prompts =
                new DesignerDecompositionPromptFactory(new ObjectMapper(), profiles, new RolePromptComposer());

        String prompt = prompts.candidate(revision(), "/managed/project", "run-42", 7,
                "DECOMPOSITION_PLAN_V2", "loopper_internal_submit_candidate");

        assertThat(prompt).contains(
                "run-42", "expectedSubmissionRevision: 7", "DECOMPOSITION_PLAN_V2",
                "loopper_internal_submit_candidate", "read, glob, and grep",
                "exactly one call", "REJECTED", "all returned problems", "same Session",
                "ACCEPTED", "WAITING_INPUT", "stop", "final text is non-authoritative");
        assertThat(prompt).contains("candidate containing one complete compact JSON", "software-python",
                "Decompose Python", "\"code\":\"MISSING_SCOPE\"", "\"detail\":");
        for (var code : DesignerSemanticContracts.DesignGapCode.values()) assertThat(prompt).contains(code.name());
        assertThat(prompt).doesNotContain("candidateJson", "write files", "execute commands");
        String example = prompt.substring(prompt.indexOf("{\"outcome\":"), prompt.indexOf("Additional globalConstraints")).strip();
        var plan = new ObjectMapper().readValue(example, DesignerSemanticContracts.CompactDecompositionPlan.class);
        assertThat(plan.workPackages()).singleElement().satisfies(item -> assertThat(item.dependsOn()).isEmpty());
        assertThat(plan.coverage()).singleElement().satisfies(item -> {
            assertThat(item.targetType()).isEqualTo("WORK_PACKAGE");
            assertThat(item.targetIndex()).isZero();
        });
    }

    private DesignRequirementRevisionRow revision() {
        return new DesignRequirementRevisionRow("rev", "session", 2, "message", "complete requirement",
                "[{\"id\":\"RQ-1\",\"text\":\"observable result\"}]", 0,
                "ACTIVE", 0, 8, "now", "now", 0);
    }
}
