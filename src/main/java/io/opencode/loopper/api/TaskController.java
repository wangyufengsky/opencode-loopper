package io.opencode.loopper.api;

import io.opencode.loopper.persistence.AttemptRow;
import io.opencode.loopper.persistence.DesignerMessageRow;
import io.opencode.loopper.persistence.DesignerSessionRow;
import io.opencode.loopper.persistence.ErrorEventRow;
import io.opencode.loopper.persistence.JudgeRunRow;
import io.opencode.loopper.persistence.LoopDraftRow;
import io.opencode.loopper.persistence.LoopperMapper;
import io.opencode.loopper.persistence.StageRow;
import io.opencode.loopper.persistence.TaskEventRow;
import io.opencode.loopper.persistence.TaskArtifactRow;
import io.opencode.loopper.persistence.TaskRow;
import io.opencode.loopper.persistence.VerificationResultRow;
import io.opencode.loopper.domain.LoopSpec;
import io.opencode.loopper.service.LoopDraftService;
import io.opencode.loopper.service.TaskEventHub;
import io.opencode.loopper.service.TaskPublicationService;
import io.opencode.loopper.service.TaskService;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
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
    public TaskController(TaskService service, LoopperMapper mapper, TaskEventHub events, ObjectMapper json,
                          LoopDraftService drafts, TaskPublicationService publication) {
        this.service = service; this.mapper = mapper; this.events = events; this.json = json;
        this.drafts = drafts; this.publication = publication;
    }
    @GetMapping public List<TaskDto> list() { return service.list().stream().map(this::dto).toList(); }
    @GetMapping("/{id}") public TaskDto get(@PathVariable String id) { return dto(service.get(id)); }
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
        return new TaskDesignHistoryDto(task.id(), task.title(), projectName,
                new TaskLoopDraftDto(draft.id(), draft.status(), draft.updatedAt(), drafts.spec(draft)),
                session == null ? null : new DesignerHistorySessionDto(session.id(), session.state(), session.accessMode(),
                        session.createdAt(), session.updatedAt(), messages));
    }
    @PostMapping("/{id}/start") public TaskDto start(@PathVariable String id) { return dto(service.start(id)); }
    @PostMapping("/{id}/verify") public TaskDto verify(@PathVariable String id) { return dto(service.verify(id)); }
    @PostMapping("/{id}/pause") public TaskDto pause(@PathVariable String id) { return dto(service.pause(id)); }
    @PostMapping("/{id}/resume") public TaskDto resume(@PathVariable String id) { return dto(service.resume(id)); }
    @PostMapping("/{id}/cancel") public TaskDto cancel(@PathVariable String id) { return dto(service.cancel(id)); }
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
    @PostMapping("/{id}/attempts/{attemptId}/session-failure") public TaskDto sessionFailure(@PathVariable String id, @PathVariable String attemptId, @RequestBody SessionFailureRequest request) {
        return dto(service.sessionFailed(id, attemptId, request.code(), request.message()));
    }
    @GetMapping(value = "/{id}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@PathVariable String id, @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId) {
        service.get(id);
        long cursor = parseCursor(lastEventId);
        SseEmitter emitter = new SseEmitter(0L);
        AtomicLong sent = new AtomicLong(cursor);
        AutoCloseable subscription = events.subscribe(id, event -> sendIfNew(emitter, sent, event));
        emitter.onCompletion(() -> close(subscription));
        emitter.onTimeout(() -> { close(subscription); emitter.complete(); });
        for (TaskEventRow event : mapper.eventsAfter(id, cursor)) sendIfNew(emitter, sent, event);
        return emitter;
    }
    private void sendIfNew(SseEmitter emitter, AtomicLong sent, TaskEventRow event) {
        if (event.sequence() <= sent.get()) return;
        try {
            synchronized (sent) {
                if (event.sequence() > sent.get()) {
                    JsonNode data = json.readTree(event.payloadJson());
                    emitter.send(SseEmitter.event().id(Long.toString(event.sequence())).data(new SseData(event.type(), event.occurredAt(), data)));
                    sent.set(event.sequence());
                }
            }
        } catch (IOException e) { emitter.complete(); }
    }
    private long parseCursor(String raw) { try { return raw == null ? 0 : Math.max(0, Long.parseLong(raw)); } catch (NumberFormatException e) { return 0; } }
    private void close(AutoCloseable value) { try { value.close(); } catch (Exception ignored) { } }
    public record SessionFailureRequest(String code, String message) { }
    public record PublishTaskRequest(String commitMessage) { }
    public record MergeRequestRequest(String targetBranch, String title, String description) { }
    public record DiffPreviewDto(String path, String changeType, String patch, boolean truncated) { }
    private TaskDto dto(TaskRow task) {
        String projectName = mapper.findProject(task.projectId()).map(p -> p.name()).orElse("Unknown project");
        LoopDraftRow draft = task.loopDraftId() == null ? null : mapper.findDraft(task.loopDraftId()).orElse(null);
        LoopSpec spec = draft == null ? null : drafts.spec(draft);
        List<AttemptRow> attempts = service.attempts(task.id());
        return new TaskDto(task.id(), task.projectId(), projectName, task.title(), spec == null ? "" : spec.goal(), task.branchName(), task.worktreePath(), task.state(),
                draft != null,
                attempts.size(), spec == null ? 0 : spec.limits().maxTaskAttempts(), task.createdAt(), task.updatedAt(),
                service.stages(task.id()).stream().map(this::stage).toList(), attempts.stream().map(this::attempt).toList(), service.errors(task.id()).stream().map(this::error).toList(),
                service.judges(task.id()).stream().map(this::judge).toList(), service.artifacts(task.id()).stream().map(this::artifact).toList());
    }
    public record SseData(String type, String at, JsonNode data) { }
    public record TaskDto(String id, String projectId, String projectName, String title, String goal, String branch,
                          String worktreePath, String status, boolean hasDesignHistory, int attemptCount, int maxAttempts, String createdAt,
                          String updatedAt, List<StageDto> stages, List<AttemptDto> attempts, List<ErrorDto> errors,
                          List<JudgeDto> judges, List<ArtifactDto> artifacts) { }
    public record TaskDesignHistoryDto(String taskId, String taskTitle, String projectName, TaskLoopDraftDto draft,
                                       DesignerHistorySessionDto designerSession) { }
    public record TaskLoopDraftDto(String id, String status, String updatedAt, LoopSpec spec) { }
    public record DesignerHistorySessionDto(String id, String state, String accessMode, String createdAt,
                                             String updatedAt, List<DesignerHistoryMessageDto> messages) { }
    public record DesignerHistoryMessageDto(String id, int ordinal, String role, String content,
                                             String deliveryState, String createdAt) { }
    public record StageDto(String id, int ordinal, String objective, String status, JsonNode allowedPaths, JsonNode forbiddenPaths,
                           JsonNode deliverables, JsonNode verifiers, String startedAt, String updatedAt) { }
    public record AttemptDto(String id, String stageId, int ordinal, String sessionId, String status, String failureKind, String summary,
                             String startedAt, String endedAt, List<VerificationDto> verifications) { }
    public record VerificationDto(String id, int verifierIndex, String type, String status, String summary, JsonNode evidence, String at) { }
    public record ErrorDto(String id, String layer, String code, String message, boolean retryable, String stageId, String attemptId, String sessionId, String at, JsonNode evidence) { }
    public record JudgeDto(String id, String role, int ordinal, String status, String verdict, String reason,
                           String externalSessionId, String rawOutput, String createdAt, String endedAt) { }
    public record ArtifactDto(String id, String kind, String name, String contentType, String content, JsonNode metadata,
                              String attemptId, String judgeRunId, String createdAt) { }
    private StageDto stage(StageRow row) { return new StageDto(row.id(), row.ordinal(), row.objective(), row.state(), node(row.allowedPathsJson()), node(row.forbiddenPathsJson()), node(row.deliverablesJson()), node(row.verifiersJson()), row.createdAt(), row.updatedAt()); }
    private AttemptDto attempt(AttemptRow row) {
        String sessionId = mapper.latestSessionForAttempt(row.id())
                .map(session -> session.externalSessionId() == null ? session.id() : session.externalSessionId())
                .orElse(null);
        return new AttemptDto(row.id(), row.stageId(), row.ordinal(), sessionId, row.state(), row.failureKind(), row.summary(),
                row.createdAt(), row.endedAt(), service.verifications(row.id()).stream().map(this::verification).toList());
    }
    private VerificationDto verification(VerificationResultRow row) { return new VerificationDto(row.id(), row.verifierIndex(), row.type(), row.state(), row.summary(), node(row.evidenceJson()), row.createdAt()); }
    private ErrorDto error(ErrorEventRow row) { return new ErrorDto(row.id(), row.layer(), row.code(), row.message(), row.retryable(), row.stageId(), row.attemptId(), row.sessionId(), row.occurredAt(), node(row.evidenceJson())); }
    private DesignerHistoryMessageDto designerHistoryMessage(DesignerMessageRow row) {
        return new DesignerHistoryMessageDto(row.id(), row.ordinal(), row.role(), row.content(), row.deliveryState(), row.createdAt());
    }
    private JudgeDto judge(JudgeRunRow row) { return new JudgeDto(row.id(), row.role(), row.ordinal(), row.state(), row.verdict(), row.reason(), row.externalSessionId(), row.rawOutput(), row.createdAt(), row.endedAt()); }
    private ArtifactDto artifact(TaskArtifactRow row) { return new ArtifactDto(row.id(), row.kind(), row.name(), row.contentType(), row.content(), node(row.metadataJson()), row.attemptId(), row.judgeRunId(), row.createdAt()); }
    private JsonNode node(String source) { try { return source == null ? json.createObjectNode() : json.readTree(source); } catch (Exception e) { return json.createObjectNode().put("unreadable", true); } }
    private void requireLocalUi(String localUi) {
        if (!"1".equals(localUi)) {
            throw new io.opencode.loopper.service.BadRequestException("LOCAL_UI_HEADER_REQUIRED", "This operation is available only to the local Loopper UI");
        }
    }
}
