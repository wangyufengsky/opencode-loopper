package io.opencode.loopper.service;

import io.opencode.loopper.domain.TaskPackageRunState;
import io.opencode.loopper.domain.TaskQueueState;
import io.opencode.loopper.domain.TaskState;
import java.util.Set;
import org.springframework.stereotype.Component;

/** Single, side-effect-free authority for rolling-package command availability. */
@Component
public final class RollingPackageCommandPolicy {
    private static final Set<TaskState> DESIGN_TASK_STATES = Set.of(
            TaskState.PACKAGE_DESIGNING, TaskState.WAITING_INPUT);
    private static final Set<String> PACKAGE_RETRY_REASONS = Set.of(
            "PACKAGE_EXECUTION_FAILED", "PACKAGE_CHECKPOINT_BLOCKED");
    private static final Set<String> JUDGE_CORRECTION_REASONS = Set.of(
            "JUDGE_CONFLICT", "JUDGE_REVIEW_NOT_APPROVED");

    public Capabilities capabilities(Context context) {
        if (blockedParent(context.taskState())) return Capabilities.none();
        TaskPackageRunState run = context.packageState();
        boolean designParent = DESIGN_TASK_STATES.contains(context.taskState());
        boolean canDiscuss = designParent && run != null
                && Set.of(TaskPackageRunState.DESIGNING, TaskPackageRunState.DESIGN_REVIEW,
                TaskPackageRunState.WAITING_INPUT).contains(run);
        boolean canApprove = designParent && run == TaskPackageRunState.DESIGN_REVIEW;
        boolean canStart = startDisposition(context) != StartDisposition.REJECTED;
        boolean canRetry = context.taskState() == TaskState.WAITING_INPUT
                && run == TaskPackageRunState.WAITING_INPUT
                && containsNullableSafe(PACKAGE_RETRY_REASONS, context.packageWaitingReason());
        boolean canRedesign = designParent && run == TaskPackageRunState.WAITING_INPUT
                && context.writerFree() && context.designerFree()
                && !"PACKAGE_CHECKPOINT_BLOCKED".equals(context.packageWaitingReason())
                && (!"PACKAGE_EXECUTION_FAILED".equals(context.packageWaitingReason()) || context.safeCheckpoint());
        boolean canResume = designParent && run == TaskPackageRunState.DESIGNING
                && context.designContinuationAvailable();
        boolean safeOwner = context.writerFree() && context.designerFree() && context.safeCheckpoint();
        boolean canReplan = designParent && safeOwner && run != null
                && Set.of(TaskPackageRunState.PLANNED, TaskPackageRunState.DESIGN_REVIEW,
                TaskPackageRunState.EXECUTION_READY,
                TaskPackageRunState.WAITING_INPUT).contains(run);
        boolean canCorrect = context.taskState() == TaskState.WAITING_INPUT
                && containsNullableSafe(JUDGE_CORRECTION_REASONS, context.taskWaitingReason())
                && safeOwner && context.frozenPackageCount() > 0;
        return new Capabilities(canDiscuss, canApprove, canStart, canRetry, canRedesign, canResume,
                canReplan, canCorrect);
    }

    public StartDisposition startDisposition(Context context) {
        if (blockedParent(context.taskState())) return StartDisposition.REJECTED;
        if (context.packageState() == TaskPackageRunState.EXECUTION_READY
                && Set.of(TaskState.PENDING_START, TaskState.WAITING_INPUT).contains(context.taskState())) {
            return StartDisposition.START;
        }
        if (context.packageState() == TaskPackageRunState.QUEUED
                && context.taskState() == TaskState.QUEUED
                && (context.queueState() == TaskQueueState.QUEUED
                || context.queueState() == TaskQueueState.ADMITTED)) {
            return StartDisposition.IDEMPOTENT;
        }
        return StartDisposition.REJECTED;
    }

    public void require(Command command, Context context) {
        Capabilities capabilities = capabilities(context);
        boolean allowed = switch (command) {
            case DISCUSS -> capabilities.canDiscuss();
            case APPROVE_DESIGN -> capabilities.canApproveDesign();
            case START -> capabilities.canStartPackage();
            case RETRY -> capabilities.canRetryPackage();
            case REDESIGN -> capabilities.canRedesignPackage();
            case RESUME_DESIGN -> capabilities.canResumeDesign();
            case REPLAN -> capabilities.canReplanRemaining();
            case ADD_CORRECTION -> capabilities.canAddCorrectionPackage();
        };
        if (!allowed) {
            throw new ConflictException("PACKAGE_COMMAND_NOT_AVAILABLE",
                    "当前任务与工作包状态不允许执行该操作，请刷新后重试");
        }
    }

    private boolean blockedParent(TaskState state) {
        return state == TaskState.AWAITING_DECISION || state == TaskState.STOPPING || state.terminal();
    }

    private boolean containsNullableSafe(Set<String> values, String candidate) {
        return candidate != null && values.contains(candidate);
    }

    public enum Command { DISCUSS, APPROVE_DESIGN, START, RETRY, REDESIGN, RESUME_DESIGN, REPLAN, ADD_CORRECTION }
    public enum StartDisposition { START, IDEMPOTENT, REJECTED }

    public record Context(TaskState taskState, String taskWaitingReason,
                          TaskPackageRunState packageState, String packageWaitingReason,
                          TaskQueueState queueState, boolean writerFree, boolean designerFree,
                          boolean designContinuationAvailable, boolean safeCheckpoint,
                          int frozenPackageCount) { }

    public record Capabilities(boolean canDiscuss, boolean canApproveDesign, boolean canStartPackage,
                               boolean canRetryPackage, boolean canRedesignPackage,
                               boolean canResumeDesign,
                               boolean canReplanRemaining, boolean canAddCorrectionPackage) {
        static Capabilities none() {
            return new Capabilities(false, false, false, false, false, false, false, false);
        }
        public boolean anyAvailable() {
            return canDiscuss || canApproveDesign || canStartPackage || canRetryPackage
                    || canRedesignPackage || canResumeDesign || canReplanRemaining || canAddCorrectionPackage;
        }
    }
}
