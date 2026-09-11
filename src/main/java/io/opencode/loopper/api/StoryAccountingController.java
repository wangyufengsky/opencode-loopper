package io.opencode.loopper.api;

import io.opencode.loopper.service.BadRequestException;
import io.opencode.loopper.service.StoryAccountingActivityService;
import io.opencode.loopper.service.StoryAccountingActivityService.CallView;
import io.opencode.loopper.service.StoryAccountingCoordinator;
import io.opencode.loopper.service.StoryAccountingEventHub;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/story-accounting")
public class StoryAccountingController {
    private final StoryAccountingActivityService activity;
    private final StoryAccountingCoordinator accounting;
    private final StoryAccountingEventHub events;
    public StoryAccountingController(StoryAccountingActivityService activity, StoryAccountingCoordinator accounting,
                                     StoryAccountingEventHub events) {
        this.activity = activity;
        this.accounting = accounting;
        this.events = events;
    }
    @GetMapping(value = "/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream() {
        SseEmitter emitter = new SseEmitter(0L);
        SseEmitterLifecycle lifecycle = new SseEmitterLifecycle();
        lifecycle.attach(events.subscribe(id -> lifecycle.send(() -> emitter.send(id.isEmpty()
                ? SseEmitter.event().comment("keepalive") : SseEmitter.event().data(id)))));
        emitter.onCompletion(lifecycle::close);
        emitter.onTimeout(lifecycle::close);
        emitter.onError(ignored -> lifecycle.close());
        // Subscribe first, then let the browser reconcile: no gap between snapshot and live delivery.
        lifecycle.send(() -> emitter.send(SseEmitter.event().name("ready").data("ready")));
        return emitter;
    }
    @GetMapping public List<CallView> list() { return activity.list(); }
    @GetMapping("/{id}") public CallView get(@PathVariable String id) { return activity.get(id); }
    @PostMapping("/{id}/cancel") public CallView cancel(@PathVariable String id,
            @RequestHeader(value = "X-Loopper-Local-UI", required = false) String localUi) {
        requireLocalUi(localUi);
        accounting.cancel(id);
        return activity.get(id);
    }
    @PostMapping("/{id}/dismiss") public void dismiss(@PathVariable String id,
            @RequestHeader(value = "X-Loopper-Local-UI", required = false) String localUi) {
        requireLocalUi(localUi);
        activity.dismiss(id);
        events.changed(id);
    }
    @PostMapping("/{id}/retry") public CallView retry(@PathVariable String id,
            @RequestHeader(value = "X-Loopper-Local-UI", required = false) String localUi) {
        requireLocalUi(localUi);
        return activity.snapshot(accounting.retry(id));
    }
    private void requireLocalUi(String value) {
        if (!"1".equals(value)) throw new BadRequestException("LOCAL_UI_HEADER_REQUIRED", "仅允许本地界面操作统计调用");
    }
}
