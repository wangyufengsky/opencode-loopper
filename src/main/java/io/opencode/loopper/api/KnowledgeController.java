package io.opencode.loopper.api;

import io.opencode.loopper.service.BadRequestException;
import io.opencode.loopper.service.KnowledgeEventHub;
import io.opencode.loopper.service.knowledge.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import java.util.Map;

@RestController
@RequestMapping("/api/knowledge/conversations")
public class KnowledgeController {
    private final KnowledgeConversations conversations;
    private final KnowledgeCoordinator coordinator;
    private final KnowledgeEventHub events;
    public KnowledgeController(KnowledgeConversations conversations, KnowledgeCoordinator coordinator, KnowledgeEventHub events) {
        this.conversations = conversations; this.coordinator = coordinator; this.events = events;
    }
    @GetMapping public CursorPage<KnowledgeConversations.View> list(@RequestParam String projectId,
            @RequestParam(required=false) String cursor, @RequestParam(required=false) Integer limit) { return conversations.list(projectId, cursor, limit); }
    @PostMapping public KnowledgeConversations.View create(@RequestBody KnowledgeConversations.Create input,
            @RequestHeader(value="X-Loopper-Local-UI",required=false) String localUi) { requireUi(localUi); return conversations.create(input); }
    @GetMapping("/{id}") public KnowledgeConversations.View get(@PathVariable String id) { return conversations.get(id); }
    @GetMapping("/{id}/messages") public CursorPage<KnowledgeConversations.Message> messages(@PathVariable String id,
            @RequestParam(required=false) String cursor, @RequestParam(required=false) Integer limit) { return conversations.messages(id, cursor, limit); }
    @GetMapping("/{id}/requests/{key}") public Map<String,Object> receipt(@PathVariable String id, @PathVariable String key) { return conversations.receipt(id, key); }
    public record Send(String idempotencyKey, String text) { }
    @PostMapping("/{id}/messages") public KnowledgeConversations.Message send(@PathVariable String id, @RequestBody Send input,
            @RequestHeader(value="X-Loopper-Local-UI",required=false) String localUi) {
        requireUi(localUi); return conversations.message(coordinator.send(id, input.idempotencyKey(), input.text()));
    }
    @PostMapping("/{id}/stop") public KnowledgeConversations.View stop(@PathVariable String id,
            @RequestHeader(value="X-Loopper-Local-UI",required=false) String localUi) { requireUi(localUi); coordinator.stop(id); return conversations.get(id); }
    @GetMapping("/{id}/citations/{citationId}") public Map<String,Object> citation(@PathVariable String id, @PathVariable String citationId) { return conversations.citation(id, citationId); }
    @GetMapping(value="/{id}/events",produces="text/event-stream") public SseEmitter stream(@PathVariable String id) {
        conversations.get(id); var emitter = new SseEmitter(0L); var lifecycle = new SseEmitterLifecycle();
        emitter.onCompletion(lifecycle::close); emitter.onTimeout(lifecycle::close); emitter.onError(failure -> lifecycle.close());
        lifecycle.attach(events.subscribe(id, event -> lifecycle.send(() -> emitter.send(SseEmitter.event().id(Long.toString(event.sequence())).data(event)))));
        lifecycle.send(() -> emitter.send(SseEmitter.event().data(Map.of("type", "connected", "conversationId", id)))); return emitter;
    }
    static void requireUi(String value) { if (!"1".equals(value)) throw new BadRequestException("LOCAL_UI_HEADER_REQUIRED", "此操作仅供本地 Loopper 界面使用"); }
}
