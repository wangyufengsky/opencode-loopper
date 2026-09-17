package io.opencode.loopper.service;

import io.opencode.loopper.persistence.LoopperMapper;
import io.opencode.loopper.persistence.TemplateTaskReadMapper;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

/** One server-owned projection for task recovery and failure-selection capabilities. */
@Service
public class TemplateTaskRecoveryStatus {
    private final LoopperMapper tasks;
    private final TemplateTaskReadMapper reads;
    private final io.opencode.loopper.persistence.TemplateBatchResilienceMapper resilience;
    public TemplateTaskRecoveryStatus(LoopperMapper tasks, TemplateTaskReadMapper reads,
            io.opencode.loopper.persistence.TemplateBatchResilienceMapper resilience) { this.tasks = tasks; this.reads = reads; this.resilience = resilience; }

    public Map<String, Long> facets(String taskId) {
        var task = tasks.findTask(taskId).orElseThrow(() -> new NotFoundException("任务不存在"));
        String code = TaskWaitingInputPolicy.reasonCode(task, tasks);
        boolean resume = task.state().equals("WAITING_INPUT") && task.worktreePath() != null
                && tasks.activeTaskExecutionCycle(taskId).isPresent() && TemplateBatchFailurePolicy.resumable(code);
        boolean retryReason = code != null && Set.of("TEMPLATE_SUBMISSION_MISSING", "TEMPLATE_MODEL_FAILED",
                "TEMPLATE_CONTENT_INVALID", "TEMPLATE_ANALYSIS_STALLED", "TEMPLATE_BATCHES_FAILED").contains(code);
        return Map.of("retrySelectionReady", retryReason && reads.retrySelectionReady(taskId) ? 1L : 0L,
                "resumeAvailable", resume ? 1L : 0L, "taskVersion", task.version(),
                "blockingBatches", reads.blockingBatchCount(taskId), "manualRoundRetryLimit", 3L,
                "environmentBlocked", resilience.dispatchBlocked(taskId, null) ? 1L : 0L);
    }
}
