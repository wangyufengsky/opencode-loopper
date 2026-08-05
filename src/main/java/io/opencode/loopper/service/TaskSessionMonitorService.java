package io.opencode.loopper.service;

import io.opencode.loopper.domain.SessionFailure;
import io.opencode.loopper.persistence.ExecutionSessionRow;
import io.opencode.loopper.persistence.JudgeRunRow;
import io.opencode.loopper.persistence.LoopperMapper;
import io.opencode.loopper.persistence.TaskRow;
import io.opencode.loopper.runtime.OpenCodeClient;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Service;

/** Read-only projection of persisted Task sessions plus provider-exposed live output. */
@Service
public class TaskSessionMonitorService {
    private final TaskService tasks;
    private final LoopperMapper mapper;
    private final OpenCodeClient openCode;

    public TaskSessionMonitorService(TaskService tasks, LoopperMapper mapper, OpenCodeClient openCode) {
        this.tasks = tasks;
        this.mapper = mapper;
        this.openCode = openCode;
    }

    public List<SessionSummary> list(String taskId) {
        tasks.get(taskId);
        List<SessionSummary> result = new ArrayList<>();
        for (ExecutionSessionRow row : mapper.listSessions(taskId)) result.add(summary(row));
        for (JudgeRunRow row : mapper.listJudgeRuns(taskId)) result.add(summary(row));
        result.sort(Comparator.comparing(SessionSummary::createdAt).reversed());
        return result;
    }

    public SessionActivity activity(String taskId, String key) {
        TaskRow task = tasks.get(taskId);
        ResolvedSession resolved = resolve(taskId, key);
        SessionSummary summary = resolved.summary();
        if (summary.externalSessionId() == null || summary.externalSessionId().isBlank() || task.worktreePath() == null || task.worktreePath().isBlank()) {
            return new SessionActivity(summary, summary.state(), false, Instant.now().toString(), List.of(),
                    "Session 尚未获得可读取的 OpenCode 远端标识或 worktree");
        }
        try {
            OpenCodeClient.OpenCodeSession remote = new OpenCodeClient.OpenCodeSession(summary.externalSessionId(), Path.of(task.worktreePath()));
            OpenCodeClient.SessionStatus status = openCode.sessionStatus(remote);
            List<ActivityPart> parts = openCode.sessionTranscript(remote).parts().stream()
                    .map(part -> new ActivityPart(part.id(), part.type(), part.label(), part.content(), part.status(), part.startedAt()))
                    .toList();
            return new SessionActivity(summary, status.state(), true, Instant.now().toString(), parts, status.detail());
        } catch (SessionFailure failure) {
            List<ActivityPart> persisted = persistedOutput(summary, resolved.persistedOutput());
            return new SessionActivity(summary, summary.state(), false, Instant.now().toString(), persisted, safe(failure.getMessage()));
        } catch (RuntimeException failure) {
            return new SessionActivity(summary, summary.state(), false, Instant.now().toString(), persistedOutput(summary, resolved.persistedOutput()), safe(failure.getMessage()));
        }
    }

    private ResolvedSession resolve(String taskId, String key) {
        if (key != null && key.startsWith("execution:")) {
            String id = key.substring("execution:".length());
            ExecutionSessionRow row = mapper.findSession(id).orElseThrow(() -> new NotFoundException("Task Session not found: " + id));
            if (!taskId.equals(row.taskId())) throw new BadRequestException("SESSION_TASK_MISMATCH", "Session does not belong to Task " + taskId);
            return new ResolvedSession(summary(row), null);
        }
        if (key != null && key.startsWith("judge:")) {
            String id = key.substring("judge:".length());
            JudgeRunRow row = mapper.findJudgeRun(id).orElseThrow(() -> new NotFoundException("Judge Session not found: " + id));
            if (!taskId.equals(row.taskId())) throw new BadRequestException("SESSION_TASK_MISMATCH", "Judge Session does not belong to Task " + taskId);
            return new ResolvedSession(summary(row), row.rawOutput());
        }
        throw new BadRequestException("SESSION_KEY_INVALID", "Session key must start with execution: or judge:");
    }

    private SessionSummary summary(ExecutionSessionRow row) {
        return new SessionSummary("execution:" + row.id(), "IMPLEMENTATION", "Implementation Session", row.id(), row.externalSessionId(),
                row.state(), row.stageId(), row.attemptId(), row.createdAt(), row.endedAt());
    }

    private SessionSummary summary(JudgeRunRow row) {
        return new SessionSummary("judge:" + row.id(), "JUDGE", row.role() + " Judge", row.id(), row.externalSessionId(),
                row.state(), null, row.attemptId(), row.createdAt(), row.endedAt());
    }

    private List<ActivityPart> persistedOutput(SessionSummary summary, String output) {
        return output == null || output.isBlank() ? List.of()
                : List.of(new ActivityPart(summary.key() + ":persisted", "OUTPUT", "已持久化模型输出", output, "COMPLETED",
                        summary.endedAt() == null ? summary.createdAt() : summary.endedAt()));
    }

    private String safe(String value) {
        if (value == null || value.isBlank()) return "OpenCode Session 暂时不可读取";
        return value.length() <= 2_000 ? value : value.substring(0, 2_000);
    }

    public record SessionSummary(String key, String kind, String label, String localSessionId, String externalSessionId,
                                 String state, String stageId, String attemptId, String createdAt, String endedAt) { }
    public record ActivityPart(String id, String type, String label, String content, String status, String startedAt) { }
    public record SessionActivity(SessionSummary session, String remoteState, boolean live, String observedAt,
                                  List<ActivityPart> parts, String detail) { }
    private record ResolvedSession(SessionSummary summary, String persistedOutput) { }
}
