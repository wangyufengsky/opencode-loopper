package io.opencode.loopper.service;

import io.opencode.loopper.domain.TaskState;
import io.opencode.loopper.persistence.LoopperMapper;
import io.opencode.loopper.persistence.TaskRow;

/** Resolves the active WAITING_INPUT gate without consulting stale historical errors. */
final class TaskWaitingInputPolicy {
    private TaskWaitingInputPolicy() { }

    static String reasonCode(TaskRow task, LoopperMapper mapper) {
        if (!TaskState.WAITING_INPUT.name().equals(task.state())) return null;
        return mapper.findTaskWaitingReasonCode(task.id()).orElse(null);
    }

    static boolean loopRetryAvailable(String reasonCode) {
        return "LOOP_STAGNATION_DETECTED".equals(reasonCode)
                || "LOOP_FRESH_SESSION_REQUIRED".equals(reasonCode);
    }

    static boolean currentDirtyWorkspaceWait(TaskRow task, String reasonCode) {
        return TaskState.WAITING_INPUT.name().equals(task.state())
                && "SOURCE_BRANCH_WORKSPACE_DIRTY".equals(reasonCode)
                && task.branchName() == null && task.worktreePath() == null;
    }
}
