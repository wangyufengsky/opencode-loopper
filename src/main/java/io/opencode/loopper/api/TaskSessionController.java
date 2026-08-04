package io.opencode.loopper.api;

import io.opencode.loopper.service.TaskSessionMonitorService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/tasks/{taskId}/sessions")
public class TaskSessionController {
    private final TaskSessionMonitorService sessions;

    public TaskSessionController(TaskSessionMonitorService sessions) { this.sessions = sessions; }

    @GetMapping
    public List<TaskSessionMonitorService.SessionSummary> list(@PathVariable String taskId) {
        return sessions.list(taskId);
    }

    @GetMapping("/{sessionKey}")
    public TaskSessionMonitorService.SessionActivity activity(@PathVariable String taskId, @PathVariable String sessionKey) {
        return sessions.activity(taskId, sessionKey);
    }
}
