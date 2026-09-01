package io.opencode.loopper.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.opencode.loopper.persistence.LoopperMapper;
import io.opencode.loopper.persistence.ProjectRow;
import io.opencode.loopper.runtime.OpenCodeClient;
import java.util.List;
import org.junit.jupiter.api.Test;

class DesignerSessionRuntimeControlTest {
    @Test
    void genericAbortRejectsAnInternalCandidateRemoteBeforeTransport() {
        LoopperMapper mapper = mock(LoopperMapper.class);
        ProjectService projects = mock(ProjectService.class);
        OpenCodeClient openCode = mock(OpenCodeClient.class);
        AcceptanceCandidateInternalTerminationIntentStore terminations =
                mock(AcceptanceCandidateInternalTerminationIntentStore.class);
        when(terminations.ownsExternalSession("internal-1")).thenReturn(true);
        DesignerSessionRuntimeControl control = new DesignerSessionRuntimeControl(
                mapper, projects, openCode, terminations);

        assertThatThrownBy(() -> control.abort("internal-1", "project-1"))
                .isInstanceOfSatisfying(ConflictException.class, failure ->
                        org.assertj.core.api.Assertions.assertThat(failure.code())
                                .isEqualTo("ACCEPTANCE_INTERNAL_TERMINATION_REQUIRED"));
        verify(openCode, never()).abortWithConfirmation(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void coordinatedReplacementStopsOnlyNonInternalDesignerRemotes() {
        LoopperMapper mapper = mock(LoopperMapper.class);
        ProjectService projects = mock(ProjectService.class);
        OpenCodeClient openCode = mock(OpenCodeClient.class);
        AcceptanceCandidateInternalTerminationIntentStore terminations =
                mock(AcceptanceCandidateInternalTerminationIntentStore.class);
        when(mapper.listDesignerRemoteSessionIds("designer-1"))
                .thenReturn(List.of("internal-1", "external-1", "external-1"));
        when(terminations.ownsExternalSession("internal-1")).thenReturn(true);
        when(projects.get("project-1")).thenReturn(new ProjectRow(
                "project-1", "Project", "/tmp/project", null, "created", "updated", 1, 0));
        when(openCode.abortWithConfirmation(org.mockito.ArgumentMatchers.any()))
                .thenReturn(OpenCodeClient.AbortConfirmation.ACKNOWLEDGED);
        DesignerSessionRuntimeControl control = new DesignerSessionRuntimeControl(
                mapper, projects, openCode, terminations);

        control.requireNonInternalDesignerSessionsStopped("designer-1", "project-1");

        verify(openCode).abortWithConfirmation(org.mockito.ArgumentMatchers.argThat(
                session -> "external-1".equals(session.id())));
        verify(openCode, never()).abortWithConfirmation(org.mockito.ArgumentMatchers.argThat(
                session -> "internal-1".equals(session.id())));
    }
}
