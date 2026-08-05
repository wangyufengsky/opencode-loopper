package io.opencode.loopper.api;

import io.opencode.loopper.persistence.DesignerMessageRow;
import io.opencode.loopper.persistence.DesignerSessionRow;
import io.opencode.loopper.persistence.ProjectRow;
import io.opencode.loopper.persistence.LoopDraftRow;
import io.opencode.loopper.service.DesignerSessionService;
import io.opencode.loopper.service.DesignerEventHub;
import io.opencode.loopper.service.LoopDraftService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** REST surface for an actual read-only OpenCode Designer handoff. */
@RestController
@RequestMapping("/api/designer-sessions")
public class DesignerSessionController {
    private final DesignerSessionService service;
    private final LoopDraftService drafts;
    private final DesignerEventHub events;

    public DesignerSessionController(DesignerSessionService service, LoopDraftService drafts, DesignerEventHub events) {
        this.service = service;
        this.drafts = drafts;
        this.events = events;
    }

    @PostMapping
    public ResponseEntity<DesignerSessionDto> create(@Valid @RequestBody CreateDesignerSessionRequest request) {
        DesignerSessionRow row = service.create(request.projectId(), request.draftId(), request.initialMessage());
        return ResponseEntity.created(URI.create("/api/designer-sessions/" + row.id())).body(dto(row));
    }

    @GetMapping("/{id}")
    public DesignerSessionDto get(@PathVariable String id) { return dto(service.get(id)); }

    @GetMapping(value = "/{id}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@PathVariable String id) {
        DesignerSessionRow current = service.get(id);
        SseEmitter emitter = new SseEmitter(0L);
        AtomicLong sent = new AtomicLong(-1L);
        AutoCloseable subscription = events.subscribe(id, event -> sendIfNew(emitter, sent, event));
        emitter.onCompletion(() -> close(subscription));
        emitter.onTimeout(() -> { close(subscription); emitter.complete(); });
        DesignerEventHub.DesignerEvent latest = events.latest(id);
        if (latest != null) sendIfNew(emitter, sent, latest);
        else sendIfNew(emitter, sent, new DesignerEventHub.DesignerEvent(0L, id, "SNAPSHOT", current.state(),
                current.externalSessionState(), false, "", "等待 OpenCode 状态探测", current.updatedAt()));
        return emitter;
    }

    private void sendIfNew(SseEmitter emitter, AtomicLong sent, DesignerEventHub.DesignerEvent event) {
        if (event.sequence() <= sent.get()) return;
        try {
            synchronized (sent) {
                if (event.sequence() > sent.get()) {
                    emitter.send(SseEmitter.event().id(Long.toString(event.sequence())).data(event));
                    sent.set(event.sequence());
                }
            }
        } catch (IOException failure) { emitter.complete(); }
    }

    private void close(AutoCloseable value) { try { value.close(); } catch (Exception ignored) { } }

    @GetMapping("/{id}/messages")
    public List<DesignerMessageDto> messages(@PathVariable String id) {
        return service.messages(id).stream().map(this::message).toList();
    }

    @PostMapping("/{id}/messages")
    public ResponseEntity<AppendMessageResult> append(@PathVariable String id, @Valid @RequestBody AppendDesignerMessageRequest request) {
        List<DesignerMessageDto> persisted = service.appendUserMessage(id, request.content()).stream().map(this::message).toList();
        DesignerSessionRow current = service.get(id);
        return ResponseEntity.accepted().body(new AppendMessageResult(id, current.state(), persisted,
                "Only actual OpenCode assistant text is persisted as an ASSISTANT message; inspect this session for delivery state."));
    }

    @PostMapping("/{id}/questions/{questionId}/reply")
    public ResponseEntity<Void> replyQuestion(@PathVariable String id, @PathVariable String questionId,
                                              @RequestBody QuestionReplyRequest request) {
        service.replyQuestion(id, questionId, request.answers());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/questions/{questionId}/reject")
    public ResponseEntity<Void> rejectQuestion(@PathVariable String id, @PathVariable String questionId) {
        service.rejectQuestion(id, questionId);
        return ResponseEntity.noContent().build();
    }

    private DesignerSessionDto dto(DesignerSessionRow row) {
        ProjectRow project = service.project(row.id());
        LoopDraftRow draft = service.draft(row.id());
        return new DesignerSessionDto(row.id(), row.projectId(), project.name(), row.state(), row.accessMode(), true,
                "Designer may read registered project context and propose LoopSpecs only. It cannot change files or start tasks.",
                row.createdAt(), row.updatedAt(), draft == null ? null : new DesignerDraftDto(
                        draft.id(), draft.status(), draft.updatedAt(), drafts.spec(draft)),
                service.messages(row.id()).stream().map(this::message).toList(), service.pendingQuestions(row.id()));
    }

    private DesignerMessageDto message(DesignerMessageRow row) {
        return new DesignerMessageDto(row.id(), row.ordinal(), row.role(), row.content(), row.deliveryState(), row.createdAt());
    }

    public record CreateDesignerSessionRequest(@NotBlank String projectId, @NotBlank String draftId,
                                               @Size(max = 12_000) String initialMessage) { }
    public record AppendDesignerMessageRequest(@NotBlank @Size(max = 12_000) String content) { }
    public record DesignerSessionDto(String id, String projectId, String projectName, String state, String accessMode,
                                     boolean readOnly, String permissionSummary, String createdAt, String updatedAt,
                                     DesignerDraftDto draft, List<DesignerMessageDto> messages,
                                     List<DesignerSessionService.PendingQuestion> pendingQuestions) { }
    public record DesignerDraftDto(String id, String status, String updatedAt,
                                   io.opencode.loopper.domain.LoopSpec spec) { }
    public record DesignerMessageDto(String id, int ordinal, String role, String content, String deliveryState, String createdAt) { }
    public record AppendMessageResult(String sessionId, String state, List<DesignerMessageDto> persistedMessages, String notice) { }
    public record QuestionReplyRequest(List<List<String>> answers) { }
}
