package io.opencode.loopper.api;

import io.opencode.loopper.persistence.AttemptRow;
import io.opencode.loopper.persistence.DesignerMessageRow;
import io.opencode.loopper.persistence.DesignerSessionRow;
import io.opencode.loopper.persistence.DesignRequirementRevisionRow;
import io.opencode.loopper.persistence.DesignWorkPackageRow;
import io.opencode.loopper.persistence.ErrorEventRow;
import io.opencode.loopper.persistence.JudgeRunRow;
import io.opencode.loopper.persistence.LoopDraftRow;
import io.opencode.loopper.persistence.LoopperMapper;
import io.opencode.loopper.persistence.StageRow;
import io.opencode.loopper.persistence.TaskEventRow;
import io.opencode.loopper.persistence.TaskArtifactRow;
import io.opencode.loopper.persistence.TaskRow;
import io.opencode.loopper.persistence.TaskDecompositionRow;
import io.opencode.loopper.persistence.VerificationResultRow;
import io.opencode.loopper.domain.LoopSpec;
import io.opencode.loopper.runtime.GitWorktreeManager;
import io.opencode.loopper.service.LoopDraftService;
import io.opencode.loopper.service.LocalSyncConflictService;
import io.opencode.loopper.service.TaskEventHub;
import io.opencode.loopper.service.TaskPublicationService;
import io.opencode.loopper.service.TaskService;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@RestController
@RequestMapping("/api/tasks")
public class TaskController {
    private final TaskService service;
    private final LoopperMapper mapper;
    private final TaskEventHub events;
    private final ObjectMapper json;
    private final LoopDraftService drafts;
    private final TaskPublicationService publication;
    private final LocalSyncConflictService localSyncConflicts;
    public TaskController(TaskService service, LoopperMapper mapper, TaskEventHub events, ObjectMapper json,
                          LoopDraftService drafts, TaskPublicationService publication,
                          LocalSyncConflictService localSyncConflicts) {
        this.service = service; this.mapper = mapper; this.events = events; this.json = json;
        this.drafts = drafts; this.publication = publication;
        this.localSyncConflicts = localSyncConflicts;
    }
    @GetMapping public List<TaskDto> list() { return service.list().stream().map(this::dto).toList(); }
    @GetMapping("/{id}") public TaskDto get(@PathVariable String id) { return dto(service.get(id)); }
    @GetMapping("/{id}/queue") public FeatureContracts.QueueStatusDto queue(@PathVariable String id) {
        return service.queueStatus(id);
    }
    @GetMapping("/{id}/diff-preview")
    public DiffPreviewDto diffPreview(@PathVariable String id, @RequestParam String path) {
        var preview = service.diffPreview(id, path);
        return new DiffPreviewDto(preview.path(), preview.changeType(), preview.patch(), preview.truncated());
    }
    @GetMapping("/{id}/design-history")
    public TaskDesignHistoryDto designHistory(@PathVariable String id) {
        TaskRow task = service.get(id);
        if (task.loopDraftId() == null || task.loopDraftId().isBlank()) {
            throw new io.opencode.loopper.service.NotFoundException("Task has no persisted LoopSpec history: " + id);
        }
        LoopDraftRow draft = mapper.findDraft(task.loopDraftId())
                .orElseThrow(() -> new io.opencode.loopper.service.NotFoundException("LoopSpec history not found for task: " + id));
        DesignerSessionRow session = mapper.findLatestDesignerSessionByDraft(draft.id()).orElse(null);
        List<DesignerHistoryMessageDto> messages = session == null ? List.of() : mapper.listDesignerMessages(session.id()).stream()
                .map(this::designerHistoryMessage).toList();
        String projectName = mapper.findProject(task.projectId()).map(p -> p.name()).orElse("Unknown project");
        DesignRequirementRevisionRow requirement = session == null ? null
                : mapper.findCurrentDesignRequirementRevision(session.id()).orElse(null);
        TaskDecompositionRow decomposition = requirement == null ? null
                : mapper.findTaskDecompositionByRevision(requirement.id()).orElse(null);
        List<DesignWorkPackageRow> packages = requirement == null ? List.of()
                : mapper.listDesignWorkPackages(requirement.id());
        return new TaskDesignHistoryDto(task.id(), task.title(), projectName,
                new TaskLoopDraftDto(draft.id(), draft.status(), draft.updatedAt(), drafts.spec(draft)),
                session == null ? null : new DesignerHistorySessionDto(session.id(), session.state(), session.accessMode(),
                        session.createdAt(), session.updatedAt(), messages),
                requirement == null ? null : new DesignRequirementHistoryDto(requirement.revision(),
                        requirement.state(), requirement.requirementText(), requirement.modelCallsUsed(),
                        requirement.maxModelCalls()),
                decomposition == null ? null : new DecompositionHistoryDto(decomposition.state(),
                        decomposition.resultType(), decomposition.planJson()),
                packages.stream().map(row -> new WorkPackageHistoryDto(row.packageId(), row.ordinal(), row.title(),
                        row.objective(), row.state(), row.compilerSummary(), row.handoffSummary())).toList());
    }
    @PostMapping("/{id}/start") public TaskDto start(@PathVariable String id) { return dto(service.start(id)); }
    @PostMapping("/{id}/verify") public TaskDto verify(@PathVariable String id) { return dto(service.verify(id)); }
    @PostMapping("/{id}/pause") public TaskDto pause(@PathVariable String id) { return dto(service.pause(id)); }
    @PostMapping("/{id}/resume") public TaskDto resume(@PathVariable String id) { return dto(service.resume(id)); }
    @PostMapping("/{id}/cancel") public TaskDto cancel(@PathVariable String id) { return dto(service.cancel(id)); }
    @PostMapping("/{id}/judges/retry")
    public TaskDto retryJudges(@PathVariable String id, @RequestHeader("X-Loopper-Local-UI") String localUi) {
        requireLocalUi(localUi);
        return dto(service.retryJudges(id));
    }
    @PostMapping("/{id}/loop/retry")
    public TaskDto retryWaitingLoop(@PathVariable String id, @RequestHeader("X-Loopper-Local-UI") String localUi) {
        requireLocalUi(localUi);
        return dto(service.retryWaitingLoop(id));
    }
    @GetMapping("/{id}/workspace-dirty")
    public WorkspaceDirtyDto workspaceDirty(@PathVariable String id) {
        return workspaceDirty(service.workspaceDirtyStatus(id));
    }
    @PostMapping("/{id}/workspace-dirty/resolve")
    public WorkspaceDirtyResolutionDto resolveWorkspaceDirty(
            @PathVariable String id, @RequestHeader("X-Loopper-Local-UI") String localUi,
            @RequestBody WorkspaceDirtyResolutionRequest request) {
        requireLocalUi(localUi);
        if (request == null || request.resolutions() == null) {
            throw new io.opencode.loopper.service.BadRequestException(
                    "SOURCE_BRANCH_WORKSPACE_RESOLUTION_REQUIRED", "Workspace cleanup decisions are required");
        }
        List<GitWorktreeManager.DirtyFileResolution> resolutions = request.resolutions().stream().map(item -> {
            try {
                return new GitWorktreeManager.DirtyFileResolution(item.path(),
                        GitWorktreeManager.DirtyFileAction.valueOf(item.action()));
            } catch (RuntimeException invalid) {
                throw new io.opencode.loopper.service.BadRequestException(
                        "SOURCE_BRANCH_WORKSPACE_ACTION_INVALID", "Dirty-file action must be COMMIT, STASH, or REMOVE");
            }
        }).toList();
        TaskService.WorkspaceDirtyResolution result = service.resolveDirtyWorkspace(
                id, request.snapshotId(), resolutions, request.commitMessage());
        return new WorkspaceDirtyResolutionDto(dto(result.task()), workspaceDirty(result.workspace()));
    }
    @PostMapping("/{id}/workspace-dirty/cancel")
    public TaskDto cancelWorkspaceDirty(
            @PathVariable String id, @RequestHeader("X-Loopper-Local-UI") String localUi) {
        requireLocalUi(localUi);
        return dto(service.failDirtyWorkspace(id));
    }
    @PutMapping("/{id}/archive")
    public TaskDto archive(@PathVariable String id, @RequestHeader("X-Loopper-Local-UI") String localUi) {
        requireLocalUi(localUi);
        return dto(service.archive(id));
    }
    @DeleteMapping("/{id}/archive")
    public TaskDto restoreArchive(@PathVariable String id, @RequestHeader("X-Loopper-Local-UI") String localUi) {
        requireLocalUi(localUi);
        return dto(service.restoreArchive(id));
    }
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteArchived(@PathVariable String id,
                                               @RequestHeader("X-Loopper-Local-UI") String localUi) {
        requireLocalUi(localUi);
        service.deleteArchived(id);
        return ResponseEntity.noContent().build();
    }
    @GetMapping("/{id}/publication")
    public TaskPublicationService.PublicationStatus publication(@PathVariable String id) {
        return publication.status(id);
    }
    @PostMapping("/{id}/publication/commit-message")
    public TaskPublicationService.CommitSuggestion generateCommitMessage(
            @PathVariable String id, @RequestHeader("X-Loopper-Local-UI") String localUi) {
        requireLocalUi(localUi);
        return publication.generateCommitMessage(id);
    }
    @PostMapping("/{id}/publication")
    public TaskPublicationService.PublicationStatus publish(
            @PathVariable String id, @RequestHeader("X-Loopper-Local-UI") String localUi,
            @RequestBody PublishTaskRequest request) {
        requireLocalUi(localUi);
        return publication.commitAndPush(id, request == null ? null : request.commitMessage());
    }
    @PostMapping("/{id}/publication/merge-request")
    public TaskPublicationService.MergeRequestDraft mergeRequest(
            @PathVariable String id, @RequestHeader("X-Loopper-Local-UI") String localUi,
            @RequestBody MergeRequestRequest request) {
        requireLocalUi(localUi);
        if (request == null) throw new io.opencode.loopper.service.BadRequestException("MERGE_REQUEST_REQUIRED", "合并请求参数不能为空");
        return publication.mergeRequestDraft(id, request.targetBranch(), request.title(), request.description());
    }
    @PostMapping("/{id}/publication/reconcile")
    public TaskPublicationService.PublicationStatus reconcilePublication(
            @PathVariable String id, @RequestHeader("X-Loopper-Local-UI") String localUi) {
        requireLocalUi(localUi);
        return publication.reconcile(id);
    }
    @PostMapping("/{id}/publication/local-conflicts")
    public LocalSyncConflictService.SessionView createOrRefreshLocalConflict(
            @PathVariable String id, @RequestHeader("X-Loopper-Local-UI") String localUi) {
        requireLocalUi(localUi);
        return localSyncConflicts.createOrRefresh(id);
    }
    @GetMapping("/{id}/publication/local-conflicts/{sessionId}")
    public LocalSyncConflictService.SessionView localConflict(
            @PathVariable String id, @PathVariable String sessionId) {
        return localSyncConflicts.get(id, sessionId);
    }
    @GetMapping("/{id}/publication/local-conflicts/{sessionId}/files")
    public List<LocalSyncConflictService.FileSummary> localConflictFiles(
            @PathVariable String id, @PathVariable String sessionId) {
        return localSyncConflicts.files(id, sessionId);
    }
    @GetMapping("/{id}/publication/local-conflicts/{sessionId}/file")
    public LocalSyncConflictService.FileContent localConflictFile(
            @PathVariable String id, @PathVariable String sessionId, @RequestParam String path) {
        return localSyncConflicts.content(id, sessionId, path);
    }
    @PutMapping("/{id}/publication/local-conflicts/{sessionId}/resolution")
    public LocalSyncConflictService.FileContent saveLocalConflictResolution(
            @PathVariable String id, @PathVariable String sessionId,
            @RequestHeader("X-Loopper-Local-UI") String localUi,
            @RequestBody LocalSyncConflictService.ResolutionRequest request) {
        requireLocalUi(localUi);
        return localSyncConflicts.saveResolution(id, sessionId, request);
    }
    @PostMapping("/{id}/publication/local-conflicts/{sessionId}/ai-suggestion")
    public LocalSyncConflictService.AiSuggestion suggestLocalConflictResolution(
            @PathVariable String id, @PathVariable String sessionId,
            @RequestHeader("X-Loopper-Local-UI") String localUi,
            @RequestBody LocalSyncConflictService.AiSuggestionRequest request) {
        requireLocalUi(localUi);
        return localSyncConflicts.suggest(id, sessionId, request);
    }
    @PostMapping("/{id}/publication/local-conflicts/{sessionId}/apply")
    public LocalSyncConflictService.SessionView applyLocalConflict(
            @PathVariable String id, @PathVariable String sessionId,
            @RequestHeader("X-Loopper-Local-UI") String localUi,
            @RequestBody LocalSyncConflictService.ApplyRequest request) {
        requireLocalUi(localUi);
        return localSyncConflicts.apply(id, sessionId, request);
    }
    @PostMapping("/{id}/attempts/{attemptId}/session-failure") public TaskDto sessionFailure(@PathVariable String id, @PathVariable String attemptId, @RequestBody SessionFailureRequest request) {
        return dto(service.sessionFailed(id, attemptId, request.code(), request.message()));
    }
    @GetMapping(value = "/{id}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@PathVariable String id, @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId) {
        service.get(id);
        long cursor = parseCursor(lastEventId);
        SseEmitter emitter = new SseEmitter(0L);
        AtomicLong sent = new AtomicLong(cursor);
        SseEmitterLifecycle lifecycle = new SseEmitterLifecycle();
        lifecycle.attach(events.subscribe(id, event -> sendIfNew(emitter, sent, lifecycle, event)));
        emitter.onCompletion(lifecycle::close);
        emitter.onTimeout(lifecycle::close);
        emitter.onError(ignored -> lifecycle.close());
        for (TaskEventRow event : mapper.eventsAfter(id, cursor)) sendIfNew(emitter, sent, lifecycle, event);
        return emitter;
    }
    private void sendIfNew(SseEmitter emitter, AtomicLong sent, SseEmitterLifecycle lifecycle, TaskEventRow event) {
        if (event.sequence() <= sent.get()) return;
        synchronized (sent) {
            if (event.sequence() > sent.get()) {
                boolean delivered = lifecycle.send(() -> {
                    JsonNode data = json.readTree(event.payloadJson());
                    emitter.send(SseEmitter.event().id(Long.toString(event.sequence())).data(new SseData(event.type(), event.occurredAt(), data)));
                });
                if (delivered) sent.set(event.sequence());
            }
        }
    }
    private long parseCursor(String raw) { try { return raw == null ? 0 : Math.max(0, Long.parseLong(raw)); } catch (NumberFormatException e) { return 0; } }
    public record SessionFailureRequest(String code, String message) { }
    public record PublishTaskRequest(String commitMessage) { }
    public record MergeRequestRequest(String targetBranch, String title, String description) { }
    public record WorkspaceDirtyFileResolutionRequest(String path, String action) { }
    public record WorkspaceDirtyResolutionRequest(String snapshotId,
                                                  List<WorkspaceDirtyFileResolutionRequest> resolutions,
                                                  String commitMessage) { }
    public record WorkspaceDirtyFileDto(String path, String originalPath, String indexStatus,
                                        String workTreeStatus, boolean untracked) { }
    public record WorkspaceDirtyDto(String branch, String head, String snapshotId, boolean clean,
                                    List<WorkspaceDirtyFileDto> files) { }
    public record WorkspaceDirtyResolutionDto(TaskDto task, WorkspaceDirtyDto workspace) { }
    public record DiffPreviewDto(String path, String changeType, String patch, boolean truncated) { }
    private TaskDto dto(TaskRow task) {
        String projectName = mapper.findProject(task.projectId()).map(p -> p.name()).orElse("Unknown project");
        LoopDraftRow draft = task.loopDraftId() == null ? null : mapper.findDraft(task.loopDraftId()).orElse(null);
        LoopSpec spec = draft == null ? null : drafts.spec(draft);
        List<AttemptRow> attempts = service.attempts(task.id());
        TaskService.LoopRetryStatus loopRetry = service.loopRetryStatus(task.id());
        List<StageRow> stageRows = service.stages(task.id());
        return new TaskDto(task.id(), task.projectId(), projectName, task.title(), spec == null ? "" : spec.goal(), task.branchName(), task.worktreePath(), task.state(),
                loopRetry.waitingReasonCode(), loopRetry.loopRetryAvailable(),
                draft != null, service.archived(task.id()),
                attempts.size(), spec == null ? 0 : spec.limits().maxTaskAttempts(), task.createdAt(), task.updatedAt(),
                stageRows.stream().map(this::stage).toList(), workPackageProgress(stageRows, attempts, spec),
                attempts.stream().map(this::attempt).toList(), service.errors(task.id()).stream().map(this::error).toList(),
                service.judges(task.id()).stream().map(this::judge).toList(), service.artifacts(task.id()).stream().map(this::artifact).toList());
    }
    public record SseData(String type, String at, JsonNode data) { }
    public record TaskDto(String id, String projectId, String projectName, String title, String goal, String branch,
                          String worktreePath, String status, String waitingReasonCode, boolean loopRetryAvailable,
                          boolean hasDesignHistory, boolean archived, int attemptCount, int maxAttempts, String createdAt,
                          String updatedAt, List<StageDto> stages, List<WorkPackageProgressDto> workPackages,
                          List<AttemptDto> attempts, List<ErrorDto> errors,
                          List<JudgeDto> judges, List<ArtifactDto> artifacts) { }
    public record TaskDesignHistoryDto(String taskId, String taskTitle, String projectName, TaskLoopDraftDto draft,
                                       DesignerHistorySessionDto designerSession,
                                       DesignRequirementHistoryDto requirement,
                                       DecompositionHistoryDto decomposition,
                                       List<WorkPackageHistoryDto> workPackages) { }
    public record TaskLoopDraftDto(String id, String status, String updatedAt, LoopSpec spec) { }
    public record DesignerHistorySessionDto(String id, String state, String accessMode, String createdAt,
                                             String updatedAt, List<DesignerHistoryMessageDto> messages) { }
    public record DesignerHistoryMessageDto(String id, int ordinal, String role, String actor, String content,
                                             String deliveryState, String createdAt,
                                             Integer requirementRevision, String workPackageId) { }
    public record DesignRequirementHistoryDto(int revision, String state, String requirementText,
                                              int modelCallsUsed, int maxModelCalls) { }
    public record DecompositionHistoryDto(String state, String resultType, String planJson) { }
    public record WorkPackageHistoryDto(String id, int ordinal, String title, String objective, String state,
                                        String compilerSummary, String handoffSummary) { }
    public record StageDto(String id, int ordinal, String objective, String status, JsonNode allowedPaths, JsonNode forbiddenPaths,
                           JsonNode deliverables, JsonNode verifiers, String startedAt, String updatedAt,
                           String workPackageId) { }
    public record WorkPackageProgressDto(String id, int ordinal, String status, int stageCount,
                                         int completedStages, int attemptCount, int attemptLimit) { }
    public record AttemptDto(String id, String stageId, int ordinal, String sessionId, String status, String failureKind, String summary,
                             String startedAt, String endedAt, List<VerificationDto> verifications) { }
    public record VerificationDto(String id, int verifierIndex, String type, String status, String summary, JsonNode evidence, String at) { }
    public record ErrorDto(String id, String layer, String code, String message, boolean retryable, String stageId, String attemptId, String sessionId, String at, JsonNode evidence) { }
    public record JudgeDto(String id, String role, int ordinal, String status, String verdict, String reason,
                           String externalSessionId, String rawOutput, String createdAt, String endedAt) { }
    public record ArtifactDto(String id, String kind, String name, String contentType, String content, JsonNode metadata,
                              String attemptId, String judgeRunId, String createdAt) { }
    private StageDto stage(StageRow row) { return new StageDto(row.id(), row.ordinal(), row.objective(), row.state(), node(row.allowedPathsJson()), node(row.forbiddenPathsJson()), node(row.deliverablesJson()), node(row.verifiersJson()), row.createdAt(), row.updatedAt(), row.workPackageId()); }
    private AttemptDto attempt(AttemptRow row) {
        String sessionId = mapper.latestSessionForAttempt(row.id())
                .map(io.opencode.loopper.persistence.ExecutionSessionRow::id)
                .orElse(null);
        return new AttemptDto(row.id(), row.stageId(), row.ordinal(), sessionId, row.state(), row.failureKind(), row.summary(),
                row.createdAt(), row.endedAt(), service.verifications(row.id()).stream().map(this::verification).toList());
    }
    private VerificationDto verification(VerificationResultRow row) { return new VerificationDto(row.id(), row.verifierIndex(), row.type(), row.state(), row.summary(), node(row.evidenceJson()), row.createdAt()); }
    private ErrorDto error(ErrorEventRow row) { return new ErrorDto(row.id(), row.layer(), row.code(), row.message(), row.retryable(), row.stageId(), row.attemptId(), row.sessionId(), row.occurredAt(), node(row.evidenceJson())); }
    private DesignerHistoryMessageDto designerHistoryMessage(DesignerMessageRow row) {
        return new DesignerHistoryMessageDto(row.id(), row.ordinal(), row.role(), row.actor(), row.content(),
                row.deliveryState(), row.createdAt(), row.requirementRevision(), row.workPackageId());
    }

    private List<WorkPackageProgressDto> workPackageProgress(List<StageRow> stages, List<AttemptRow> attempts,
                                                             LoopSpec spec) {
        if (spec == null) return List.of();
        Map<String, List<StageRow>> grouped = new java.util.LinkedHashMap<>();
        for (StageRow stage : stages) if (stage.workPackageId() != null && !stage.workPackageId().isBlank()) {
            grouped.computeIfAbsent(stage.workPackageId(), ignored -> new java.util.ArrayList<>()).add(stage);
        }
        int[] ordinal = {0};
        return grouped.entrySet().stream().map(entry -> {
            List<StageRow> packageStages = entry.getValue();
            java.util.Set<String> ids = packageStages.stream().map(StageRow::id).collect(java.util.stream.Collectors.toSet());
            int complete = (int) packageStages.stream().filter(stage -> "SUCCEEDED".equals(stage.state())).count();
            String state = packageStages.stream().anyMatch(stage -> "FAILED".equals(stage.state())) ? "FAILED"
                    : complete == packageStages.size() ? "SUCCEEDED"
                    : packageStages.stream().anyMatch(stage -> List.of("RUNNING", "PAUSED").contains(stage.state()))
                    ? "RUNNING" : "PENDING";
            int used = (int) attempts.stream().filter(attempt -> ids.contains(attempt.stageId())).count();
            int limit = Math.min(packageStages.size() * spec.limits().maxStageAttempts(), packageStages.size() + 2);
            return new WorkPackageProgressDto(entry.getKey(), ordinal[0]++, state, packageStages.size(),
                    complete, used, limit);
        }).toList();
    }
    private JudgeDto judge(JudgeRunRow row) { return new JudgeDto(row.id(), row.role(), row.ordinal(), row.state(), row.verdict(), row.reason(), row.externalSessionId(), row.rawOutput(), row.createdAt(), row.endedAt()); }
    private ArtifactDto artifact(TaskArtifactRow row) { return new ArtifactDto(row.id(), row.kind(), row.name(), row.contentType(), row.content(), node(row.metadataJson()), row.attemptId(), row.judgeRunId(), row.createdAt()); }
    private JsonNode node(String source) { try { return source == null ? json.createObjectNode() : json.readTree(source); } catch (Exception e) { return json.createObjectNode().put("unreadable", true); } }
    private WorkspaceDirtyDto workspaceDirty(GitWorktreeManager.DirtyWorkspace workspace) {
        return new WorkspaceDirtyDto(workspace.branch(), workspace.head(), workspace.snapshotId(), workspace.clean(),
                workspace.files().stream().map(file -> new WorkspaceDirtyFileDto(
                        file.path(), file.originalPath(), file.indexStatus(), file.workTreeStatus(), file.untracked())).toList());
    }
    private void requireLocalUi(String localUi) {
        if (!"1".equals(localUi)) {
            throw new io.opencode.loopper.service.BadRequestException("LOCAL_UI_HEADER_REQUIRED", "This operation is available only to the local Loopper UI");
        }
    }
}
