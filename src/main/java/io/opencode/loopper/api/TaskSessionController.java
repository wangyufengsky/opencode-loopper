package io.opencode.loopper.api;

import io.opencode.loopper.service.TaskSessionMonitorService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
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

    @PostMapping("/{sessionKey}/questions/{questionId}/reply")
    public void reply(@PathVariable String taskId, @PathVariable String sessionKey, @PathVariable String questionId,
                      @RequestBody QuestionReplyRequest request) {
        sessions.reply(taskId, sessionKey, questionId, request.answers());
    }

    @PostMapping("/{sessionKey}/questions/{questionId}/reject")
    public void reject(@PathVariable String taskId, @PathVariable String sessionKey, @PathVariable String questionId) {
        sessions.reject(taskId, sessionKey, questionId);
    }

    public record QuestionReplyRequest(List<List<String>> answers) { }
}
