package io.opencode.loopper.service;

import io.opencode.loopper.domain.JudgeReviewBatchState;
import io.opencode.loopper.domain.LifecycleEvent;
import io.opencode.loopper.domain.LifecycleMachineType;
import io.opencode.loopper.domain.LifecycleScopeType;
import io.opencode.loopper.lifecycle.LifecycleTransitionService;
import io.opencode.loopper.persistence.AttemptRow;
import io.opencode.loopper.persistence.JudgeReviewBatchRow;
import io.opencode.loopper.persistence.LoopperMapper;
import io.opencode.loopper.persistence.TaskExecutionCycleRow;
import io.opencode.loopper.persistence.TaskRow;
import java.time.Instant;
import java.util.Comparator;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Owns generation-safe Requirement/Risk review batches independently from each Judge retry row. */
@Service
final class JudgeReviewBatchService {
    private final LoopperMapper mapper;
    private final LifecycleTransitionService lifecycle;

    JudgeReviewBatchService(LoopperMapper mapper, LifecycleTransitionService lifecycle) {
        this.mapper = mapper;
        this.lifecycle = lifecycle;
    }

    JudgeReviewBatchRow create(TaskRow task, AttemptRow finalAttempt) {
        if (task == null || finalAttempt == null || !task.id().equals(finalAttempt.taskId())
                || finalAttempt.executionCycleId() == null || finalAttempt.executionCycleId().isBlank()) {
            throw new ConflictException("JUDGE_REVIEW_BATCH_SOURCE_INVALID",
                    "双评审批次需要当前任务的最终执行轮次与 Attempt");
        }
        TaskExecutionCycleRow reviewCycle = mapper.activeTaskExecutionCycle(task.id()).orElseThrow(() ->
                new ConflictException("JUDGE_REVIEW_BATCH_CYCLE_MISSING", "双评审缺少活动的最终评审轮次"));
        boolean sameCycle = reviewCycle.id().equals(finalAttempt.executionCycleId());
        boolean frozenRollingAttempt = "FINAL_REVIEW".equals(reviewCycle.cycleType())
                && mapper.listPackageFactSnapshots(task.id()).stream()
                .anyMatch(fact -> finalAttempt.id().equals(fact.successfulAttemptId()));
        if (!sameCycle && !frozenRollingAttempt) {
            throw new ConflictException("JUDGE_REVIEW_BATCH_SOURCE_INVALID",
                    "最终 Attempt 不属于当前评审轮次或已冻结的滚动工作包事实");
        }
        mapper.listJudgeReviewBatches(task.id()).stream()
                .filter(row -> JudgeReviewBatchState.RUNNING.name().equals(row.state()))
                .findAny().ifPresent(row -> { throw new ConflictException(
                        "JUDGE_REVIEW_BATCH_ALREADY_RUNNING", "任务已有运行中的双评审批次"); });
        int generation = mapper.listJudgeReviewBatches(task.id()).stream()
                .map(JudgeReviewBatchRow::generation).max(Comparator.naturalOrder()).orElse(0) + 1;
        String now = Instant.now().toString();
        JudgeReviewBatchRow row = new JudgeReviewBatchRow(
                UUID.randomUUID().toString(), task.id(), reviewCycle.id(),
                finalAttempt.id(), generation, JudgeReviewBatchState.RUNNING.name(),
                now, now, null, 0);
        lifecycle.create(subject(row), row.state(), Map.of("generation", generation),
                () -> mapper.insertJudgeReviewBatch(row),
                () -> new ConflictException("JUDGE_REVIEW_BATCH_CREATE_CONFLICT",
                        "双评审批次未能持久化"));
        return require(row.id());
    }

    JudgeReviewBatchRow require(String id) {
        return mapper.findJudgeReviewBatch(id)
                .orElseThrow(() -> new NotFoundException("Judge review batch not found: " + id));
    }

    Optional<JudgeReviewBatchRow> findRunning(String taskId) {
        return mapper.listJudgeReviewBatches(taskId).stream()
                .filter(row -> JudgeReviewBatchState.RUNNING.name().equals(row.state()))
                .max(Comparator.comparingInt(JudgeReviewBatchRow::generation));
    }

    Optional<JudgeReviewBatchRow> latest(String taskId) {
        return mapper.listJudgeReviewBatches(taskId).stream()
                .max(Comparator.comparingInt(JudgeReviewBatchRow::generation));
    }

    JudgeReviewBatchRow transition(String id, JudgeReviewBatchState target) {
        JudgeReviewBatchRow current = require(id);
        JudgeReviewBatchState state = JudgeReviewBatchState.valueOf(current.state());
        if (state == target) return current;
        if (state.terminal() || target == JudgeReviewBatchState.RUNNING) {
            throw new ConflictException("JUDGE_REVIEW_BATCH_STATE_CONFLICT",
                    "双评审批次已经终态或目标状态无效");
        }
        String now = Instant.now().toString();
        JudgeReviewBatchRow updated = new JudgeReviewBatchRow(
                current.id(), current.taskId(), current.executionCycleId(), current.finalAttemptId(),
                current.generation(), target.name(), current.createdAt(), now, now, current.version());
        LifecycleEvent event = switch (target) {
            case COMPLETED -> LifecycleEvent.COMPLETE;
            case WAITING_INPUT -> LifecycleEvent.REQUIRE_INPUT;
            case CANCELLED -> LifecycleEvent.CANCEL;
            case RUNNING -> throw new IllegalArgumentException("RUNNING is not a terminal target");
        };
        lifecycle.transition(subject(current), current.state(), target.name(), event,
                "JUDGE_REVIEW_BATCH_" + target.name(), Map.of("generation", current.generation()),
                () -> mapper.updateJudgeReviewBatch(updated),
                () -> new ConflictException("JUDGE_REVIEW_BATCH_VERSION_CONFLICT",
                        "双评审批次被并发更新"));
        return require(id);
    }

    private static LifecycleTransitionService.Subject subject(JudgeReviewBatchRow row) {
        return new LifecycleTransitionService.Subject(LifecycleMachineType.JUDGE_REVIEW_BATCH,
                row.id(), LifecycleScopeType.TASK, row.taskId());
    }
}
