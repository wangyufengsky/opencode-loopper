package io.opencode.loopper.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.opencode.loopper.domain.LifecycleEvent;
import io.opencode.loopper.domain.StageState;
import io.opencode.loopper.domain.TaskState;
import io.opencode.loopper.persistence.LoopperMapper;
import io.opencode.loopper.persistence.JudgeReviewBatchRow;
import io.opencode.loopper.persistence.StageRow;
import io.opencode.loopper.persistence.TaskRow;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

class TaskTerminalConsistencyServiceTest {
    @Test
    void completionClosesConfirmedDesignerBeforeWritingTerminalTask() {
        Fixture fixture = new Fixture();
        TaskRow completed = fixture.task(TaskState.COMPLETED);
        when(fixture.states.taskState(fixture.task, TaskState.COMPLETED)).thenReturn(completed);

        fixture.service.complete(fixture.task, LifecycleEvent.COMPLETE, Map.of("confirmation", "TEST"));

        verify(fixture.rolling).requireTerminalRuns(fixture.task.id());
        verify(fixture.designers).completeTaskDesignerInTransaction(fixture.task.id());
        verify(fixture.states).updateTask(completed, LifecycleEvent.COMPLETE, Map.of("confirmation", "TEST"));
    }

    @Test
    void completionRejectsAnyNonterminalChildWithoutTouchingDesignerOrTask() {
        Fixture fixture = new Fixture();
        StageRow running = new StageRow("stage", fixture.task.id(), 0, "running", "[]", "[]", "[]", "[]",
                StageState.RUNNING.name(), "2026-01-01T00:00:00Z", "2026-01-01T00:00:00Z", 0);
        when(fixture.mapper.listStages(fixture.task.id())).thenReturn(List.of(running));

        assertThatThrownBy(() -> fixture.service.complete(fixture.task, LifecycleEvent.COMPLETE, Map.of()))
                .isInstanceOfSatisfying(ConflictException.class, failure ->
                        org.assertj.core.api.Assertions.assertThat(failure.code())
                                .isEqualTo("TASK_TERMINAL_CHILDREN_ACTIVE"));
        verify(fixture.designers, never()).completeTaskDesignerInTransaction(fixture.task.id());
        verify(fixture.states, never()).updateTask(any(), any(), any());
    }

    @Test
    void completionDoesNotWriteTheTaskWhenTheDesignerCandidateGuardRejectsIt() {
        Fixture fixture = new Fixture();
        doThrow(new ConflictException("DESIGNER_CANDIDATE_WRITER_STILL_ACTIVE", "active"))
                .when(fixture.designers).completeTaskDesignerInTransaction(fixture.task.id());

        assertThatThrownBy(() -> fixture.service.complete(fixture.task, LifecycleEvent.COMPLETE, Map.of()))
                .isInstanceOfSatisfying(ConflictException.class, failure ->
                        org.assertj.core.api.Assertions.assertThat(failure.code())
                                .isEqualTo("DESIGNER_CANDIDATE_WRITER_STILL_ACTIVE"));
        verify(fixture.states, never()).updateTask(any(), any(), any());
    }

    @Test
    void completionExplicitlyRejectsUnstoppedHandoffCleanupUnderAnyParentState() {
        Fixture fixture = new Fixture();
        when(fixture.mapper.existsUnstoppedAcceptanceCandidateHandoffCleanupForTask(fixture.task.id()))
                .thenReturn(true);

        assertThatThrownBy(() -> fixture.service.complete(fixture.task, LifecycleEvent.COMPLETE, Map.of()))
                .isInstanceOfSatisfying(ConflictException.class, failure ->
                        org.assertj.core.api.Assertions.assertThat(failure.code())
                                .isEqualTo("TASK_TERMINAL_CHILDREN_ACTIVE"));
        verify(fixture.designers, never()).completeTaskDesignerInTransaction(fixture.task.id());
        verify(fixture.states, never()).updateTask(any(), any(), any());
    }

    @Test
    void completionRejectsRunningJudgeReviewBatchEvenWhenNoJudgeRowIsActive() {
        Fixture fixture = new Fixture();
        when(fixture.mapper.listJudgeReviewBatches(fixture.task.id())).thenReturn(List.of(
                new JudgeReviewBatchRow("batch", fixture.task.id(), "cycle", "attempt", 1,
                        "RUNNING", "now", "now", null, 0)));

        assertThatThrownBy(() -> fixture.service.complete(fixture.task, LifecycleEvent.COMPLETE, Map.of()))
                .isInstanceOfSatisfying(ConflictException.class, failure ->
                        org.assertj.core.api.Assertions.assertThat(failure.code())
                                .isEqualTo("TASK_TERMINAL_CHILDREN_ACTIVE"));
        verify(fixture.states, never()).updateTask(any(), any(), any());
    }

    private static final class Fixture {
        private final LoopperMapper mapper = mock(LoopperMapper.class);
        private final TaskStateStore states = mock(TaskStateStore.class);
        private final RollingPackageTaskHooks rolling = mock(RollingPackageTaskHooks.class);
        private final DesignerTerminationService designers = mock(DesignerTerminationService.class);
        private final TaskRow task = task(TaskState.AWAITING_DECISION);
        private final TaskTerminalConsistencyService service;

        private Fixture() {
            PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
            when(transactionManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
            when(mapper.findTask(task.id())).thenReturn(Optional.of(task));
            when(mapper.listAttempts(task.id())).thenReturn(List.of());
            when(mapper.listStages(task.id())).thenReturn(List.of());
            when(mapper.listTaskExecutionCycles(task.id())).thenReturn(List.of());
            when(mapper.listJudgeReviewBatches(task.id())).thenReturn(List.of());
            when(mapper.activeJudgeRuns(task.id())).thenReturn(List.of());
            when(mapper.findTaskQueue(task.id())).thenReturn(Optional.empty());
            when(mapper.findActiveWorkspaceLeaseByHolder(task.id())).thenReturn(Optional.empty());
            service = new TaskTerminalConsistencyService(mapper, states, rolling, designers, transactionManager);
        }

        private TaskRow task(TaskState state) {
            return new TaskRow("task", "project", "draft", "title", state.name(), null, null, null, null,
                    "2026-01-01T00:00:00Z", "2026-01-01T00:00:00Z", 0);
        }
    }
}
