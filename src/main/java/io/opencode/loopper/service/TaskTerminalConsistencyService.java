package io.opencode.loopper.service;

import io.opencode.loopper.domain.AttemptState;
import io.opencode.loopper.domain.ExecutionCycleState;
import io.opencode.loopper.domain.JudgeReviewBatchState;
import io.opencode.loopper.domain.LifecycleEvent;
import io.opencode.loopper.domain.StageState;
import io.opencode.loopper.domain.TaskQueueState;
import io.opencode.loopper.domain.TaskState;
import io.opencode.loopper.persistence.LoopperMapper;
import io.opencode.loopper.persistence.TaskRow;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Prevents a Task terminal transition from outrunning any owned aggregate. */
@Service
final class TaskTerminalConsistencyService {
    private static final Set<String> TERMINAL_STAGES = Set.of(
            StageState.SUCCEEDED.name(), StageState.FAILED.name(), StageState.CANCELLED.name());
    private final LoopperMapper mapper;
    private final TaskStateStore states;
    private final RollingPackageTaskHooks rolling;
    private final DesignerTerminationService designers;
    private final TransactionTemplate transactions;

    TaskTerminalConsistencyService(LoopperMapper mapper, TaskStateStore states, RollingPackageTaskHooks rolling,
                                   DesignerTerminationService designers,
                                   PlatformTransactionManager transactionManager) {
        this.mapper = mapper;
        this.states = states;
        this.rolling = rolling;
        this.designers = designers;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    TaskRow complete(TaskRow from, LifecycleEvent event, Map<String, ?> metadata) {
        transactions.executeWithoutResult(ignored -> {
            requireClosedExecution(from.id());
            rolling.requireTerminalRuns(from.id());
            designers.completeTaskDesignerInTransaction(from.id());
            states.updateTask(states.taskState(current(from.id()), TaskState.COMPLETED), event, metadata);
        });
        return current(from.id());
    }

    TaskRow supersede(TaskRow from, Map<String, ?> metadata) {
        DesignerTerminationService.Result remote = designers.stopTaskDesignerRemotely(from.id());
        if (remote.failedSessions() > 0) {
            throw new ServiceUnavailableException("TASK_DESIGNER_STOP_UNCONFIRMED",
                    "任务设计会话尚未确认停止，父任务保持待处置状态");
        }
        transactions.executeWithoutResult(ignored -> {
            designers.finalizeTaskDesignerInTransaction(from.id());
            rolling.supersedeRunsInTransaction(from.id());
            requireClosedExecution(from.id());
            states.updateTask(states.taskState(current(from.id()), TaskState.SUPERSEDED),
                    LifecycleEvent.SUPERSEDE, metadata);
        });
        return current(from.id());
    }

    private void requireClosedExecution(String taskId) {
        boolean openAttempt = mapper.listAttempts(taskId).stream()
                .anyMatch(row -> AttemptState.RUNNING.name().equals(row.state()));
        boolean openStage = mapper.listStages(taskId).stream().anyMatch(row -> !TERMINAL_STAGES.contains(row.state()));
        boolean openCycle = mapper.listTaskExecutionCycles(taskId).stream()
                .anyMatch(row -> !ExecutionCycleState.valueOf(row.state()).terminal());
        boolean openQueue = mapper.findTaskQueue(taskId).map(row -> Set.of(
                TaskQueueState.QUEUED.name(), TaskQueueState.ADMITTED.name()).contains(row.state())).orElse(false);
        boolean openCandidateCleanup = mapper.existsUnstoppedAcceptanceCandidateHandoffCleanupForTask(taskId);
        boolean openJudge = !mapper.activeJudgeRuns(taskId).isEmpty();
        boolean openJudgeBatch = mapper.listJudgeReviewBatches(taskId).stream()
                .anyMatch(row -> JudgeReviewBatchState.RUNNING.name().equals(row.state()));
        if (openAttempt || openStage || openCycle || openQueue || openCandidateCleanup || openJudge || openJudgeBatch
                || mapper.findActiveWorkspaceLeaseByHolder(taskId).isPresent()) {
            throw new ConflictException("TASK_TERMINAL_CHILDREN_ACTIVE",
                    "任务仍有未收束的执行子状态、队列或租约，不能进入终态");
        }
    }

    private TaskRow current(String taskId) {
        return mapper.findTask(taskId).orElseThrow(() -> new NotFoundException("Task not found: " + taskId));
    }
}
