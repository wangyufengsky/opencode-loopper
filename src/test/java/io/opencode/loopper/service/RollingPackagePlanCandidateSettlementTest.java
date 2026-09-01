package io.opencode.loopper.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.opencode.loopper.domain.LifecycleEvent;
import io.opencode.loopper.lifecycle.LifecycleTransitionService;
import io.opencode.loopper.persistence.CandidateSubmissionRunRow;
import io.opencode.loopper.persistence.DesignerSessionRow;
import io.opencode.loopper.persistence.LoopperMapper;
import io.opencode.loopper.persistence.RollingPackagePlanAcceptedResultRow;
import io.opencode.loopper.persistence.TaskPackagePlanRevisionRow;
import io.opencode.loopper.persistence.TaskPackageRunRow;
import io.opencode.loopper.persistence.TaskRow;
import io.opencode.loopper.persistence.TaskWorkspaceCheckpointRow;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntSupplier;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;
import tools.jackson.databind.ObjectMapper;

class RollingPackagePlanCandidateSettlementTest {
    @Test
    void acceptedResultAndGeneratingOwnerSettleTogetherOnlyAfterPositiveStopProof() throws Exception {
        LoopperMapper mapper = mock(LoopperMapper.class);
        ObjectMapper json = new ObjectMapper();
        RollingPackagePlanCompilation compilation = new DeterministicRollingPackagePlanCompilation(json);
        RollingPackagePlanCompilationInputLoader inputs = mock(RollingPackagePlanCompilationInputLoader.class);
        LifecycleTransitionService lifecycle = mock(LifecycleTransitionService.class);
        TaskWorkspaceCheckpointService checkpoints = mock(TaskWorkspaceCheckpointService.class);
        TaskEventService events = mock(TaskEventService.class);
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        when(transactionManager.getTransaction(any(TransactionDefinition.class)))
                .thenReturn(new SimpleTransactionStatus());
        TaskPackagePlanRevisionRow owner = owner();
        when(mapper.findTaskPackagePlanRevision(owner.id())).thenReturn(Optional.of(owner));
        TaskRow task = mock(TaskRow.class);
        when(task.version()).thenReturn(owner.baseTaskVersion());
        TaskPackageRunRow baseRun = mock(TaskPackageRunRow.class);
        when(baseRun.version()).thenReturn(owner.basePackageVersion());
        TaskWorkspaceCheckpointRow checkpoint = mock(TaskWorkspaceCheckpointRow.class);
        when(checkpoint.state()).thenReturn("READY");
        when(mapper.findTask(owner.taskId())).thenReturn(Optional.of(task));
        when(mapper.findTaskPackageRun(owner.basePackageRunId())).thenReturn(Optional.of(baseRun));
        when(mapper.findTaskWorkspaceCheckpoint(owner.baseCheckpointId())).thenReturn(Optional.of(checkpoint));
        when(mapper.findDesignerSession(owner.designerSessionId())).thenReturn(Optional.of(mock(DesignerSessionRow.class)));
        when(mapper.activeTaskPackagePlanRevision(owner.taskId())).thenReturn(Optional.of(mock(TaskPackagePlanRevisionRow.class)));
        when(checkpoints.designSnapshot(task, checkpoint)).thenReturn(java.nio.file.Path.of("/tmp/snapshot"));
        RollingPackagePlanCompilation.Input frozen = new RollingPackagePlanCompilation.Input(List.of(
                new RollingPackagePlanCompilation.CurrentPackage("package-run-2", "WP-2", List.of("WP-1"))),
                List.of("WP-1"), List.of());
        when(inputs.loadTask(owner.taskId())).thenReturn(frozen);
        String candidate = """
                {"packages":[{"packageKey":"WP-2","title":"业务接入","objective":"接入业务",
                "replaces":["WP-2"],"dependencies":["WP-1"],"requirementRefs":[]}]}
                """;
        RollingPackagePlanCompilation.Result compiled = compilation.compileCandidate(frozen, candidate);
        RollingPackagePlanAcceptedResultRow accepted = new RollingPackagePlanAcceptedResultRow(
                "candidate-run", owner.id(), owner.revision(), 1, "ROLLING_PACKAGE_PLAN_V1",
                compiled.canonicalCandidateJson(), compiled.canonicalPlanJson(), compiled.canonicalImpactJson(),
                "a".repeat(64), null, "now", "now", 0);
        when(mapper.findRollingPackagePlanAcceptedResult("candidate-run")).thenReturn(Optional.of(accepted));
        when(mapper.findCandidateSubmissionRun("candidate-run")).thenReturn(Optional.of(new CandidateSubmissionRunRow(
                "candidate-run", null, owner.taskId(), null, "TASK_PACKAGE_PLAN_REVISION", owner.id(),
                "ROLLING_PACKAGE_PLAN_V1", "ROLLING_PACKAGE_PLAN_V1", owner.revision(), 1,
                "INTERNAL_MCP", "ROLLING_PACKAGE_PLAN_V1", "generation-1", owner.externalSessionId(),
                "ACCEPTED", 3, 1, "attempt-1", "now", "now", 1)));
        AtomicReference<TaskPackagePlanRevisionRow> updatedOwner = new AtomicReference<>();
        when(mapper.updateTaskPackagePlanRevision(any())).thenAnswer(call -> {
            updatedOwner.set(call.getArgument(0));
            return 1;
        });
        when(mapper.settleRollingPackagePlanAcceptedResult(
                eq("candidate-run"), eq(0L), eq(owner.id()), any()))
                .thenReturn(1);
        doAnswer(call -> {
            assertThat(((IntSupplier) call.getArgument(6)).getAsInt()).isEqualTo(1);
            return null;
        }).when(lifecycle).transition(any(), eq("GENERATING"), eq("PROPOSED"),
                eq(LifecycleEvent.COMPLETE_PACKAGE_REPLAN), isNull(), anyMap(), any(IntSupplier.class), any());
        RollingPackagePlanService service = new RollingPackagePlanService(mapper, json,
                mock(RollingPackageService.class), lifecycle, checkpoints, mock(WorkPackageRoleService.class),
                events, mock(RollingPackageCommandPolicy.class), compilation, inputs, transactionManager);

        service.completeCandidateSuggestion(owner, accepted, "ABORT_ACKNOWLEDGED");

        assertThat(updatedOwner.get()).satisfies(row -> {
            assertThat(row.state()).isEqualTo("PROPOSED");
            assertThat(row.externalSessionState()).isEqualTo("ABORT_ACKNOWLEDGED");
            assertThat(row.planJson()).isEqualTo(compiled.canonicalPlanJson());
            assertThat(row.impactJson()).isEqualTo(compiled.canonicalImpactJson());
        });
        verify(mapper).settleRollingPackagePlanAcceptedResult(
                eq("candidate-run"), eq(0L), eq(owner.id()), any());
    }

    private TaskPackagePlanRevisionRow owner() {
        return new TaskPackagePlanRevisionRow("plan-1", "task-1", "designer-1", "requirement-1",
                4, "GENERATING", "AI", "[]", "{}", "remote-1", "RUNNING",
                null, null, "checkpoint-1", 7, "package-run-2", 3,
                "2026-09-02T00:00:00Z", "2026-09-02T00:00:00Z", null, null, 2);
    }
}
