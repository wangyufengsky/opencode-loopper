package io.opencode.loopper.api;

import io.opencode.loopper.persistence.TemplateTaskReadMapper;
import io.opencode.loopper.service.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/template-tasks")
public class TemplateTaskController {
    private final TemplateTaskService templates;
    private final ProjectBranchService branches;
    private final TemplateTaskReadService reads;
    private final TaskService tasks;
    private final TemplateBatchRetryService retries;
    public TemplateTaskController(TemplateTaskService templates, ProjectBranchService branches, TemplateTaskReadService reads, TaskService tasks, TemplateBatchRetryService retries) {
        this.templates = templates; this.branches = branches; this.reads = reads; this.tasks = tasks; this.retries = retries;
    }
    @GetMapping("/catalog") public TemplateTaskService.Catalog catalog() { return templates.catalog(); }
    @GetMapping("/projects/{id}") public TemplateTaskReadMapper.ProjectChoice project(@PathVariable String id) { return reads.project(id); }
    @GetMapping("/projects") public CursorPage<TemplateTaskReadMapper.ProjectChoice> projects(
            @RequestParam(required = false) String query, @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "50") int limit) { return reads.projects(query, cursor, limit); }
    @GetMapping("/projects/{projectId}/branches") public ProjectBranchService.Page branches(@PathVariable String projectId,
            @RequestParam(required = false) String query, @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "50") int limit) { return branches.list(projectId, query, cursor, limit); }
    @GetMapping public CursorPage<TemplateTaskReadMapper.RunSummary> runs(@RequestParam(required = false) String projectId,
            @RequestParam(required = false) String cursor, @RequestParam(defaultValue = "50") int limit) { return reads.runs(projectId, cursor, limit); }
    @PostMapping public Created create(@RequestHeader(value = "X-Loopper-Local-UI", required = false) String localUi,
            @RequestBody TemplateTaskService.Request request) {
        requireLocalUi(localUi);
        var task = templates.create(request, false);
        return new Created(task.id(), task.state());
    }
    @PostMapping("/{taskId}/start") public ResponseEntity<Created> start(@PathVariable String taskId,
            @RequestHeader(value = "X-Loopper-Local-UI", required = false) String localUi) {
        requireLocalUi(localUi);
        if (!TemplateWorkspaceService.applies(tasks.get(taskId))) throw new BadRequestException("TEMPLATE_TASK_REQUIRED", "请选择模板任务");
        var task = tasks.start(taskId);
        return ResponseEntity.accepted().body(new Created(task.id(), task.state()));
    }
    @GetMapping("/{taskId}/failed-batches") public CursorPage<TemplateTaskReadMapper.FailedBatch> failedBatches(
            @PathVariable String taskId, @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "50") int limit) {
        if (!TemplateWorkspaceService.applies(tasks.get(taskId))) throw new BadRequestException("TEMPLATE_TASK_REQUIRED", "请选择模板任务");
        return reads.failedBatches(taskId, cursor, limit);
    }
    @GetMapping("/{taskId}/snapshot-batches") public CursorPage<TemplateTaskReadMapper.SnapshotBatchSummary> snapshotBatches(
            @PathVariable String taskId, @RequestParam(defaultValue = "") String after, @RequestParam(defaultValue = "50") int limit) {
        if (!TemplateWorkspaceService.applies(tasks.get(taskId))) throw new BadRequestException("TEMPLATE_TASK_REQUIRED", "请选择模板任务");
        return reads.snapshotBatches(taskId, after, limit);
    }
    @PostMapping("/{taskId}/batches/{batchId}/retry") public Created retry(@PathVariable String taskId,
            @PathVariable String batchId, @RequestHeader(value = "X-Loopper-Local-UI", required = false) String localUi,
            @RequestBody Retry request) {
        requireLocalUi(localUi);
        var batch = retries.retry(taskId, batchId, request.expectedVersion());
        return new Created(batch.id(), batch.state());
    }
    @PostMapping("/{taskId}/batches/retry") public java.util.List<Created> retrySelected(@PathVariable String taskId,
            @RequestHeader(value = "X-Loopper-Local-UI", required = false) String localUi,
            @RequestBody BatchRetrySelection request) {
        requireLocalUi(localUi);
        return retries.retrySelected(taskId, request).stream().map(row -> new Created(row.id(), row.state())).toList();
    }
    public record Retry(long expectedVersion) { }

    private static void requireLocalUi(String value) {
        if (!"1".equals(value)) throw new BadRequestException("LOCAL_UI_HEADER_REQUIRED", "请从本地页面发起模板任务");
    }
    public record Created(String id, String state) { }
}
