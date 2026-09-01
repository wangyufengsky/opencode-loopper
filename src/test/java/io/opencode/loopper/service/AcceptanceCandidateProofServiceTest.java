package io.opencode.loopper.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.opencode.loopper.domain.MachineCandidateKind;
import io.opencode.loopper.domain.MachineCandidateRunState;
import io.opencode.loopper.lifecycle.LifecycleTransitionService;
import io.opencode.loopper.persistence.CandidateSubmissionRunRow;
import io.opencode.loopper.persistence.DesignerSessionRow;
import io.opencode.loopper.persistence.LoopperMapper;
import io.opencode.loopper.persistence.LoopSpecCompilationRow;
import java.util.Optional;
import java.util.function.IntSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

class AcceptanceCandidateProofServiceTest {
    private final LoopperMapper mapper = mock(LoopperMapper.class);
    private final LifecycleTransitionService lifecycle = mock(LifecycleTransitionService.class);
    private final AcceptanceCandidateProofService service =
            new AcceptanceCandidateProofService(mapper, lifecycle, Optional.empty());

    @Test
    void persistsProofOnlyAfterRevalidatingTheOriginalRunAndOwner() {
        MachineCandidateSubmission.RunSnapshot run = run();
        LoopSpecCompilationRow owner = owner("remote-1", 8);
        DesignerSessionRow session = mock(DesignerSessionRow.class);
        when(session.state()).thenReturn("RUNNING");
        when(mapper.findCandidateSubmissionRun("run-1")).thenReturn(Optional.of(storedRun()));
        when(mapper.findLoopSpecCompilation("compilation-1")).thenReturn(Optional.of(owner));
        when(mapper.findDesignerSession("designer-1")).thenReturn(Optional.of(session));
        when(mapper.updateLoopSpecCompilation(any())).thenReturn(1);
        doAnswer(invocation -> {
            ((IntSupplier) invocation.getArgument(0)).getAsInt();
            return null;
        }).when(lifecycle).mutateWithoutTransition(any(), any());

        LoopSpecCompilationRow persisted = service.persistIfOwned(run, "ABORT_ACKNOWLEDGED").orElseThrow();

        assertThat(persisted.externalSessionState()).isEqualTo("ABORT_ACKNOWLEDGED");
        assertThat(persisted.version()).isEqualTo(9);
        ArgumentCaptor<LoopSpecCompilationRow> update = ArgumentCaptor.forClass(LoopSpecCompilationRow.class);
        verify(mapper).updateLoopSpecCompilation(update.capture());
        assertThat(update.getValue().externalSessionId()).isEqualTo("remote-1");
        assertThat(update.getValue().designRevision()).isEqualTo(3);
    }

    @Test
    void rejectsProofWhenTheOwnerSessionWasReplacedAfterRemoteIo() {
        when(mapper.findCandidateSubmissionRun("run-1")).thenReturn(Optional.of(storedRun()));
        when(mapper.findLoopSpecCompilation("compilation-1"))
                .thenReturn(Optional.of(owner("replacement-remote", 9)));
        DesignerSessionRow session = mock(DesignerSessionRow.class);
        when(session.state()).thenReturn("RUNNING");
        when(mapper.findDesignerSession("designer-1")).thenReturn(Optional.of(session));

        assertThat(service.persistIfOwned(run(), "ABORT_ACKNOWLEDGED")).isEmpty();
        verify(mapper, never()).updateLoopSpecCompilation(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"STOPPING", "CANCELLED"})
    void rejectsProofWhileTheDesignerOwnerIsStoppingOrCancelled(String ownerState) {
        when(mapper.findCandidateSubmissionRun("run-1")).thenReturn(Optional.of(storedRun()));
        when(mapper.findLoopSpecCompilation("compilation-1")).thenReturn(Optional.of(owner("remote-1", 8)));
        DesignerSessionRow session = mock(DesignerSessionRow.class);
        when(session.state()).thenReturn(ownerState);
        when(mapper.findDesignerSession("designer-1")).thenReturn(Optional.of(session));

        assertThat(service.persistIfOwned(run(), "ABORT_ACKNOWLEDGED")).isEmpty();
        verify(mapper, never()).updateLoopSpecCompilation(any());
    }

    private MachineCandidateSubmission.RunSnapshot run() {
        return new MachineCandidateSubmission.RunSnapshot(
                "run-1", MachineCandidateSubmission.CandidateScope.designerSession("designer-1"),
                MachineCandidateSubmission.CandidateOwnerRef.loopSpecCompilation("compilation-1"),
                MachineCandidateKind.ACCEPTANCE_CLOSED_CHOICE_V7,
                AcceptanceClosedChoiceCandidateCoordinator.WORKFLOW_STEP, 3, 7,
                MachineCandidateSubmission.SubmissionChannel.INTERNAL_MCP,
                AcceptanceClosedChoiceCandidateCoordinator.CONTRACT_VERSION,
                "generation-1", "remote-1", MachineCandidateRunState.ACCEPTED,
                2, 1, "attempt-1", 1);
    }

    private CandidateSubmissionRunRow storedRun() {
        return new CandidateSubmissionRunRow(
                "run-1", "designer-1", null, null, "LOOP_SPEC_COMPILATION", "compilation-1",
                "ACCEPTANCE_CLOSED_CHOICE_V7", "ACCEPTANCE_CLOSED_CHOICE_V7", 3, 7,
                "INTERNAL_MCP", "ACCEPTANCE_CLOSED_CHOICE_V7", "generation-1", "remote-1",
                "ACCEPTED", 2, 1, "attempt-1", "now", "now", 1);
    }

    private LoopSpecCompilationRow owner(String remote, long version) {
        return new LoopSpecCompilationRow(
                "compilation-1", "designer-1", 3, "RUNNING", remote, "RUNNING", 0,
                "message-1", 1, null, null, "now", "now", version);
    }
}
