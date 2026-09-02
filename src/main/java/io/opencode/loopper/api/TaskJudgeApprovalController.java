package io.opencode.loopper.api;

import io.opencode.loopper.service.BadRequestException;
import io.opencode.loopper.service.TaskJudgeApprovalService;
import io.opencode.loopper.service.TaskService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/tasks/{taskId}/judge-approval")
public class TaskJudgeApprovalController {
    private final TaskJudgeApprovalService approvals;
    private final TaskService tasks;
    public TaskJudgeApprovalController(TaskJudgeApprovalService approvals, TaskService tasks) {
        this.approvals = approvals; this.tasks = tasks;
    }
    @GetMapping
    public TaskJudgeApprovalService.View get(@PathVariable String taskId) { return approvals.view(taskId); }
    @PostMapping
    public TaskJudgeApprovalService.View approve(@PathVariable String taskId,
            @RequestHeader(value = "X-Loopper-Local-UI", required = false) String localUi,
            @RequestBody TaskJudgeApprovalService.Request request) {
        if (!"1".equals(localUi)) throw new BadRequestException("LOCAL_UI_HEADER_REQUIRED", "人工认定必须由本地界面明确确认");
        tasks.continueAfterLeaseReconciliation(approvals.approve(taskId, request));
        return approvals.view(taskId);
    }
}
