package io.opencode.loopper.api;

import io.opencode.loopper.service.BadRequestException;
import io.opencode.loopper.service.SessionLifecycleService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Local-only mutation endpoints for durable execution-session snapshots. */
@RestController
@RequestMapping("/api/tasks/{taskId}/sessions/{sessionId}")
public class SessionLifecycleController {
    private final SessionLifecycleService lifecycle;
    public SessionLifecycleController(SessionLifecycleService lifecycle) { this.lifecycle = lifecycle; }

    @GetMapping("/todos") public List<SessionLifecycleService.TodoDto> todos(@PathVariable String taskId, @PathVariable String sessionId) { return lifecycle.todos(taskId, sessionId); }
    @PostMapping("/todos/refresh") public List<SessionLifecycleService.TodoDto> refreshTodos(@PathVariable String taskId, @PathVariable String sessionId, @RequestHeader(value = "X-Loopper-Local-UI", required = false) String localUi) { requireLocalUi(localUi); return lifecycle.refreshTodos(taskId, sessionId); }
    @GetMapping("/checkpoints") public List<SessionLifecycleService.CheckpointDto> checkpoints(@PathVariable String taskId, @PathVariable String sessionId) { return lifecycle.checkpoints(taskId, sessionId); }
    @PostMapping("/checkpoints") public SessionLifecycleService.CheckpointDto checkpoint(@PathVariable String taskId, @PathVariable String sessionId, @RequestHeader(value = "X-Loopper-Local-UI", required = false) String localUi, @RequestBody(required = false) CheckpointRequest request) { requireLocalUi(localUi); return lifecycle.checkpoint(taskId, sessionId, request == null ? null : request.externalMessageId()); }
    @PostMapping("/fork") public SessionLifecycleService.ForkDto fork(@PathVariable String taskId, @PathVariable String sessionId, @RequestHeader(value = "X-Loopper-Local-UI", required = false) String localUi, @RequestBody ForkRequest request) { requireLocalUi(localUi); return lifecycle.fork(taskId, sessionId, request == null ? null : request.messageId()); }
    @PostMapping("/revert") public SessionLifecycleService.RevertDto revert(@PathVariable String taskId, @PathVariable String sessionId, @RequestHeader(value = "X-Loopper-Local-UI", required = false) String localUi, @RequestBody RevertRequest request) { requireLocalUi(localUi); return lifecycle.revert(taskId, sessionId, request == null ? null : request.messageId(), request == null ? null : request.partId()); }
    @PostMapping("/summarize") public SessionLifecycleService.SummaryDto summarize(@PathVariable String taskId, @PathVariable String sessionId, @RequestHeader(value = "X-Loopper-Local-UI", required = false) String localUi, @RequestBody(required = false) SummarizeRequest request) { requireLocalUi(localUi); return lifecycle.summarize(taskId, sessionId, request != null && request.automatic()); }

    private void requireLocalUi(String value) { if (!"1".equals(value)) throw new BadRequestException("LOCAL_UI_HEADER_REQUIRED", "This operation is available only to the local Loopper UI"); }
    public record CheckpointRequest(String externalMessageId) { }
    public record ForkRequest(String messageId) { }
    public record RevertRequest(String messageId, String partId) { }
    public record SummarizeRequest(boolean automatic) { }
}
