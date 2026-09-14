package io.opencode.loopper.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import io.opencode.loopper.persistence.*;
import java.util.*;
import org.junit.jupiter.api.*;

class DocumentDevelopmentExecutionTest {
    private final TaskService tasks = mock(TaskService.class);
    private final RollingPackageService rolling = mock(RollingPackageService.class);
    private final TaskReadService reads = mock(TaskReadService.class);
    private final LoopperMapper domain = mock(LoopperMapper.class);
    private final DocumentDevelopmentEvidence evidence = mock(DocumentDevelopmentEvidence.class);
    private final DocumentDevelopmentExecution execution = new DocumentDevelopmentExecution(tasks, rolling, reads, domain, evidence);
    private final DocumentTemplateRunRow run = mock(DocumentTemplateRunRow.class);
    private final TaskRow task = mock(TaskRow.class);
    private final TaskReadService.TaskOverview overview = mock(TaskReadService.TaskOverview.class);
    @BeforeEach void prepare() {
        when(run.taskId()).thenReturn("task"); when(run.projectId()).thenReturn("project");
        when(tasks.get("task")).thenReturn(task); when(task.id()).thenReturn("task"); when(task.projectId()).thenReturn("project");
        when(task.version()).thenReturn(7L); when(task.state()).thenReturn("PENDING_START"); when(reads.overview("task")).thenReturn(overview);
    }
    @Test void singlePackageStartsOnlyThroughFormalStartAndNeverDisposition() {
        assertThat(execution.advance(run).waiting()).isFalse(); verify(tasks).start("task", "AUTOMATION");
        when(task.state()).thenReturn("RUNNING"); execution.advance(run); verify(tasks, times(1)).start(anyString(), anyString());
        verify(tasks, never()).acceptResult(anyString());
    }
    @Test void eachRollingStepUsesServerCapabilitiesAndExactOwnerVersions() {
        when(task.state()).thenReturn("PACKAGE_DESIGNING");
        when(overview.currentPackage()).thenReturn(new TaskReadService.CurrentPackage("pack", "WP-2", 1, "第二包", "DESIGN_REVIEW", 4));
        var pack = new TaskPackageRunRow("pack", "task", "plan", "design", "WP-2", 1, "第二包", "DESIGN_REVIEW", null, 3, 5, null, null, "now", "now", 4);
        when(domain.findTaskPackageRun("pack")).thenReturn(Optional.of(pack));
        when(overview.packageCapabilities()).thenReturn(new TaskReadService.PackageCapabilities(true, true, false, false, false, false, false, false));
        execution.advance(run); verify(rolling).approveDocumentDesign("task", "pack", 7, 4, 3, 5);
        when(overview.packageCapabilities()).thenReturn(new TaskReadService.PackageCapabilities(false, false, true, false, false, false, false, false));
        execution.advance(run); verify(tasks).startRollingPackage("task", "pack", 7, 4);
        when(overview.packageCapabilities()).thenReturn(new TaskReadService.PackageCapabilities(false, false, false, false, false, true, false, false));
        execution.advance(run); verify(rolling).resumeDesign("task", "pack", 7, 4);
    }
    @Test void failedVerificationDirtyFilesAndManualPauseRemainActionableInsteadOfAutoRetrying() {
        for (String state : List.of("WAITING_INPUT", "PAUSED", "STOPPING")) {
            when(task.state()).thenReturn(state); assertThat(execution.advance(run).waiting()).isTrue();
        }
        verify(tasks, never()).start(anyString(), anyString()); verify(tasks, never()).resume(anyString());
        verify(tasks, never()).retryWaitingLoop(anyString()); verifyNoInteractions(evidence);
    }
    @Test void resultStateRequiresCompletionProofAndNeverAcceptsOnBehalfOfUser() {
        when(task.state()).thenReturn("AWAITING_DECISION");
        doThrow(new ConflictException("NOT_PASSED", "双评审未通过")).when(evidence).freeze(run);
        assertThatThrownBy(() -> execution.advance(run)).isInstanceOf(ConflictException.class);
        verify(tasks, never()).acceptResult(anyString());
    }
    @Test void cancellationWaitsForBothWriterAndDesignerStopProof() {
        var termination = mock(DesignerTerminationService.class);
        var generations = mock(RollingPackagePlanGenerationService.class); when(generations.stopDocumentTask(anyString())).thenReturn(true);
        var stop = new DocumentDevelopmentStop(tasks, termination, generations, mock(DocumentSupplementMapper.class), mock(RollingPackagePlanService.class), mock(DocumentSupplementDesign.class));
        when(run.designerId()).thenReturn("designer"); when(task.state()).thenReturn("STOPPING");
        when(tasks.continueCancellation("task")).thenReturn(task);
        assertThat(stop.stop(run, true)).isFalse(); verifyNoInteractions(termination);
        when(task.state()).thenReturn("CANCELLED"); when(tasks.writersStopped("task")).thenReturn(false);
        assertThat(stop.stop(run, true)).isFalse(); verifyNoInteractions(termination);
        when(tasks.writersStopped("task")).thenReturn(true);
        when(termination.stop("designer", false)).thenReturn(new DesignerTerminationService.Result("STOPPING", false, 0, 1, 0));
        assertThat(stop.stop(run, true)).isFalse();
        when(termination.stop("designer", false)).thenReturn(new DesignerTerminationService.Result("CANCELLED", false, 1, 0, 0));
        assertThat(stop.stop(run, true)).isTrue();
    }
}
