package io.opencode.loopper.service;

import io.opencode.loopper.domain.ExecutionCycleState;
import io.opencode.loopper.domain.LifecycleEvent;
import io.opencode.loopper.domain.TaskState;
import io.opencode.loopper.persistence.LoopperMapper;
import io.opencode.loopper.persistence.TaskRow;
import java.util.Map;
import org.springframework.stereotype.Service;

/** Closes a validated report through the shared stop, lease and aggregate terminal guards. */
@Service
final class TemplateTaskCompletionService {
    private final LoopperMapper mapper;
    private final TemplateRunEvidenceService evidence;
    private final TaskExecutionCycleService cycles;
    private final TemplateWorkspaceService workspace;
    private final TaskTerminalConsistencyService terminal;

    TemplateTaskCompletionService(LoopperMapper mapper, TemplateRunEvidenceService evidence,
            TaskExecutionCycleService cycles,
            TemplateWorkspaceService workspace, TaskTerminalConsistencyService terminal) {
        this.mapper = mapper; this.evidence = evidence; this.cycles = cycles;
        this.workspace = workspace; this.terminal = terminal;
    }

    boolean requiresDualReview(String taskId) { return evidence.contract(taskId).requiresDualReview(); }

    TaskRow complete(String taskId, boolean hasUnconfirmedWriter) {
        TaskRow task = mapper.findTask(taskId).orElseThrow(() -> new NotFoundException("模板任务不存在"));
        if (!TemplateWorkspaceService.applies(task) || !TaskState.AWAITING_DECISION.name().equals(task.state())) return task;
        var cycle = cycles.latest(taskId);
        if (cycle == null || !ExecutionCycleState.SUCCEEDED.name().equals(cycle.state()) || hasUnconfirmedWriter) return task;
        if (!workspace.releaseStopped(task)) return task;
        return terminal.complete(mapper.findTask(taskId).orElseThrow(), LifecycleEvent.COMPLETE, Map.of("source",
                requiresDualReview(taskId) ? "TEMPLATE_REPORT_DUAL_JUDGE_PASS" : "TEMPLATE_REPORT_VALIDATED"));
    }
}
