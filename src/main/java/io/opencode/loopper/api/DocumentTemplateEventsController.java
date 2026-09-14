package io.opencode.loopper.api;

import io.opencode.loopper.service.DocumentProgressEvents;
import io.opencode.loopper.service.DocumentTemplateReadService;
import io.opencode.loopper.service.ServiceUnavailableException;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/template-tasks/document-runs")
public final class DocumentTemplateEventsController {
    private final DocumentTemplateReadService reads;
    private final DocumentProgressEvents events;
    private final Semaphore connections = new Semaphore(128);
    public DocumentTemplateEventsController(DocumentTemplateReadService reads, DocumentProgressEvents events) {
        this.reads = reads; this.events = events;
    }
    @GetMapping(value = "/{id}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(@PathVariable String id) {
        reads.overview(id);
        if (!connections.tryAcquire()) throw new ServiceUnavailableException("DOCUMENT_EVENTS_BUSY", "实时连接较多，页面会通过状态读取继续刷新");
        var emitter = new SseEmitter(60_000L); var lifecycle = new SseEmitterLifecycle(); var released = new AtomicBoolean();
        Runnable close = () -> { lifecycle.close(); if (released.compareAndSet(false, true)) connections.release(); };
        emitter.onCompletion(close); emitter.onTimeout(close); emitter.onError(ignored -> close.run());
        lifecycle.attach(events.subscribe(id, changed -> {
            if (!lifecycle.send(() -> emitter.send(SseEmitter.event().name("progress").data(Map.of("runId", changed))))) close.run();
        }));
        // Last-Event-ID is deliberately not an authority: every connection invalidates its REST snapshot.
        if (!lifecycle.send(() -> emitter.send(SseEmitter.event().name("progress").data(Map.of("runId", id))))) close.run();
        return emitter;
    }
}
