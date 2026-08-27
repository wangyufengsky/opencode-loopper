package io.opencode.loopper.service;

import io.opencode.loopper.domain.TaskPackageRunState;
import io.opencode.loopper.domain.TaskQueueState;
import io.opencode.loopper.domain.TaskState;
import io.opencode.loopper.persistence.LoopperMapper;
import io.opencode.loopper.persistence.TaskOverviewRow;
import io.opencode.loopper.persistence.TaskPackageRunRow;
import io.opencode.loopper.persistence.TaskRow;
import java.util.Set;
import org.springframework.stereotype.Service;

/** Builds the persisted owner and checkpoint facts shared by package reads and commands. */
@Service
public final class RollingPackageCommandContextService {
    private static final Set<String> ACTIVE_VERIFIER_STATES = Set.of(
            "STARTING", "RUNNING", "STOPPING", "DISCONNECTED");
    private final LoopperMapper mapper;

    public RollingPackageCommandContextService(LoopperMapper mapper) {
        this.mapper = mapper;
    }

    RollingPackageCommandPolicy.Context context(TaskRow task, TaskPackageRunRow run) {
        return context(task.id(), TaskState.valueOf(task.state()),
                mapper.findTaskWaitingReasonCode(task.id()).orElse(null), run);
    }

    RollingPackageCommandPolicy.Context context(TaskOverviewRow task, TaskPackageRunRow run) {
        return context(task.id(), TaskState.valueOf(task.state()), task.waitingReasonCode(), run);
    }

    private RollingPackageCommandPolicy.Context context(String taskId, TaskState taskState,
                                                         String taskWaitingReason, TaskPackageRunRow run) {
        boolean safeCheckpoint = mapper.listPackageFactSnapshots(taskId).stream().reduce((left, right) -> right)
                .flatMap(fact -> mapper.findTaskWorkspaceCheckpoint(fact.checkpointId()))
                .map(checkpoint -> Set.of("READY", "RESTORED").contains(checkpoint.state())).orElse(false);
        boolean writerFree = mapper.activeSessions(taskId).isEmpty()
                && mapper.activeJudgeRuns(taskId).isEmpty()
                && mapper.listVerifierRuntimes(taskId).stream()
                .noneMatch(runtime -> ACTIVE_VERIFIER_STATES.contains(runtime.state()));
        boolean designerFree = mapper.findDesignerSessionByTask(taskId)
                .map(session -> !"RUNNING".equals(session.state())
                        && mapper.countActiveDesignWorkPackages(session.id()) == 0)
                .orElse(true);
        TaskQueueState queueState = mapper.findTaskQueue(taskId)
                .map(row -> TaskQueueState.valueOf(row.state())).orElse(null);
        int frozen = (int) mapper.listTaskPackageRuns(taskId).stream()
                .filter(row -> TaskPackageRunState.FACT_FROZEN.name().equals(row.state())).count();
        return new RollingPackageCommandPolicy.Context(taskState, taskWaitingReason,
                run == null ? null : TaskPackageRunState.valueOf(run.state()),
                run == null ? null : run.waitingReasonCode(), queueState, writerFree, designerFree,
                safeCheckpoint, frozen);
    }
}
