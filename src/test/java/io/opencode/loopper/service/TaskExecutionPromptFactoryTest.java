package io.opencode.loopper.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.opencode.loopper.domain.LoopSpec;
import io.opencode.loopper.domain.TaskFailure;
import io.opencode.loopper.domain.ImplementationKind;
import io.opencode.loopper.persistence.*;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class TaskExecutionPromptFactoryTest {
    private final ObjectMapper json = new ObjectMapper();
    private final LoopperTaskMapper mapper = mock(LoopperTaskMapper.class);
    private final TaskExecutionPromptFactory prompts = new TaskExecutionPromptFactory(mapper, json, new RolePromptComposer());

    @Test void currentStageAcceptanceAndRuntimeSurviveStaleDesignAndRetryContext() {
        LoopSpec.StageSpec first = stage("first-stage-only", "do not leak this rubric");
        LoopSpec.StageSpec current = stage("current-user-acceptance", "current-user-rubric");
        LoopSpec spec = new LoopSpec("v2", "project", "goal", "context", List.of(first, current), null, null, null, null);
        TaskRow task = mock(TaskRow.class);
        when(task.id()).thenReturn("task");
        when(task.branchName()).thenReturn("loopper/task");
        when(mapper.findFirstTaskArtifactByKind("task", "DESIGN_CONTEXT")).thenReturn(Optional.of(
                new TaskArtifactRow("artifact", "task", null, null, "DESIGN_CONTEXT", "design", "text/plain",
                        "Older design uses a different acceptance threshold", "{}", "now")));
        String prompt = prompts.prompt(task, spec, row(1), Path.of("."), "previous failed attempt");
        String encoded = prompt.substring(prompt.indexOf("runtime):\n") + "runtime):\n".length());
        encoded = encoded.substring(0, encoded.indexOf("\nImplement every"));
        assertThat(json.readValue(encoded, LoopSpec.StageSpec.class)).isEqualTo(current);
        assertThat(prompt).contains("current-user-acceptance", "current-user-rubric", "{{LOOPPER_PORT}}",
                "Loopper starts and stops verificationRuntime", "previous failed attempt", "Older design")
                .doesNotContain("first-stage-only", "do not leak this rubric");
    }

    @Test void missingStageContractStopsBeforeProducingAnExecutionPrompt() {
        LoopSpec spec = new LoopSpec("v2", "project", "goal", "", List.of(stage("a", "b")), null, null, null, null);
        for (int ordinal : List.of(-1, 1)) {
            assertThatThrownBy(() -> prompts.prompt(mock(TaskRow.class), spec, row(ordinal), Path.of("."), ""))
                    .isInstanceOf(TaskFailure.class).hasMessageContaining("matching frozen StageSpec");
        }
        verifyNoInteractions(mapper);
    }

    private LoopSpec.StageSpec stage(String description, String rubric) {
        return new LoopSpec.StageSpec("objective", List.of("src/**"), List.of(".env"), List.of("deliverable"),
                List.of(), List.of(new LoopSpec.AcceptanceCriterion("AC-1", description, "JUDGE", rubric, "subjective")),
                new LoopSpec.VerificationRuntime(List.of("java", "-jar", "app.jar", "--server.port={{LOOPPER_PORT}}"),
                        new LoopSpec.RuntimeReadiness("/health", 200, null, null, null), 60, 10),
                ImplementationKind.JAVA_PRODUCTION);
    }

    private StageRow row(int ordinal) {
        return new StageRow("stage", "task", ordinal, "objective", "[\"src/**\"]", "[\".env\"]", "[]", "[]",
                "RUNNING", "now", "now", 0);
    }
}
