package io.opencode.loopper.api;

import io.opencode.loopper.domain.RecoveryMode;
import io.opencode.loopper.persistence.StageRow;
import io.opencode.loopper.persistence.TaskExecutionCycleRow;
import io.opencode.loopper.persistence.TaskRow;
import io.opencode.loopper.persistence.TaskWorkspaceCheckpointRow;
import io.opencode.loopper.service.ConflictException;
import io.opencode.loopper.service.RecoveryService;
import io.opencode.loopper.service.TaskService;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TaskDecisionControllerTest {
    private final TaskService tasks = mock(TaskService.class);
    private final RecoveryService recovery = mock(RecoveryService.class);
    private final TaskDecisionController controller = new TaskDecisionController(tasks, recovery, new ObjectMapper());

    @Test
    void successfulFrozenResultOffersPublicationContinuationAndNoChangeAcceptance() {
        TaskRow task = task(7);
        TaskExecutionCycleRow cycle = cycle("SUCCEEDED", 4);
        TaskWorkspaceCheckpointRow checkpoint = checkpoint("READY", "[]");
        when(tasks.get(task.id())).thenReturn(task);
        when(tasks.latestExecutionCycle(task.id())).thenReturn(cycle);
        when(tasks.latestWorkspaceCheckpoint(task.id())).thenReturn(checkpoint);
        when(tasks.stages(task.id())).thenReturn(List.of(stage()));

        TaskDecisionController.DecisionDto view = controller.get(task.id());

        assertThat(view.availableActions()).contains("CONTINUE_CURRENT_TASK", "DERIVE_INHERIT_CHANGES",
                "DERIVE_REWORK_ALL", "READ_ONLY_AUDIT", "PUBLISH", "ACCEPT_RESULT", "CANCEL");
        assertThat(view.checkpoint().changedFileCount()).isZero();
    }

    @Test
    void staleDecisionCannotCreateADerivedWriter() {
        TaskRow task = task(8);
        when(tasks.get(task.id())).thenReturn(task);
        when(tasks.latestExecutionCycle(task.id())).thenReturn(cycle("FAILED", 3));

        assertThatThrownBy(() -> controller.derive(task.id(), "1",
                new TaskDecisionController.DeriveRequest(7, 3, RecoveryMode.INHERIT_CHANGES)))
                .isInstanceOfSatisfying(ConflictException.class,
                        failure -> assertThat(failure.code()).isEqualTo("TASK_DECISION_STALE"));
    }

    @Test
    void auditCreatesOnlyTheExistingVerifyOnlyRecoveryContract() {
        TaskRow task = task(2);
        TaskExecutionCycleRow cycle = cycle("FAILED", 5);
        when(tasks.get(task.id())).thenReturn(task);
        when(tasks.latestExecutionCycle(task.id())).thenReturn(cycle);
        FeatureContracts.RecoveryDto expected = new FeatureContracts.RecoveryDto(
                "audit-task", task.id(), RecoveryMode.VERIFY_ONLY, "stage-1", "tree", false);
        when(recovery.create(task.id(), RecoveryMode.VERIFY_ONLY)).thenReturn(expected);

        assertThat(controller.audit(task.id(), "1", new TaskDecisionController.VersionRequest(2, 5)))
                .isEqualTo(expected);
        verify(recovery).create(task.id(), RecoveryMode.VERIFY_ONLY);
    }

    @Test
    void cancellationUsesTheDedicatedResultDispositionCommand() {
        TaskRow task = task(6);
        TaskExecutionCycleRow cycle = cycle("FAILED", 4);
        TaskRow cancelled = new TaskRow(task.id(), task.projectId(), task.loopDraftId(), task.title(), "CANCELLED",
                task.worktreePath(), task.branchName(), task.sourceBranch(), task.baselineCommit(),
                task.createdAt(), "later", 8);
        when(tasks.get(task.id())).thenReturn(task);
        when(tasks.latestExecutionCycle(task.id())).thenReturn(cycle);
        when(tasks.cancelDecision(task.id())).thenReturn(cancelled);
        when(tasks.stages(task.id())).thenReturn(List.of(stage()));

        TaskDecisionController.DecisionDto result = controller.cancel(task.id(), "1",
                new TaskDecisionController.VersionRequest(6, 4));

        assertThat(result.taskState()).isEqualTo("CANCELLED");
        verify(tasks).cancelDecision(task.id());
        verify(tasks, never()).cancel(task.id());
    }

    private TaskRow task(long version) {
        return new TaskRow("task-1", "project-1", "draft-1", "Task", "AWAITING_DECISION",
                "/tmp/project", "loopper/task", "main", "base", "now", "now", version);
    }

    private TaskExecutionCycleRow cycle(String state, long version) {
        return new TaskExecutionCycleRow("cycle-1", "task-1", 1, "INITIAL", state, null, null,
                null, "{}", null, null, "now", "now", "later", version);
    }

    private TaskWorkspaceCheckpointRow checkpoint(String state, String manifest) {
        return new TaskWorkspaceCheckpointRow("checkpoint-1", "task-1", "cycle-1", state,
                "snapshot", "/tmp/project", "fingerprint", "loopper/task", "main", "base",
                "refs/loopper/checkpoints/task-1/cycle-1", "commit", "tree", manifest, "sha", "stash",
                null, null, "now", "now", 1);
    }

    private StageRow stage() {
        return new StageRow("stage-1", "task-1", 0, "Implement", "[]", "[]", "[]", "[]",
                "SUCCEEDED", "now", "now", 0, null);
    }
}
