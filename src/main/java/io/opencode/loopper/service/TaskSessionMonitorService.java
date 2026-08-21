package io.opencode.loopper.service;

import io.opencode.loopper.domain.SessionFailure;
import io.opencode.loopper.domain.JudgeRunState;
import io.opencode.loopper.domain.SessionState;
import io.opencode.loopper.domain.TaskState;
import io.opencode.loopper.domain.TodoCapability;
import io.opencode.loopper.persistence.ExecutionSessionRow;
import io.opencode.loopper.persistence.JudgeRunRow;
import io.opencode.loopper.persistence.LoopperMapper;
import io.opencode.loopper.persistence.StageRow;
import io.opencode.loopper.persistence.SessionTodoRow;
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
    private final ModelTokenUsageProjectionService tokenUsage;

    public TaskSessionMonitorService(TaskService tasks, LoopperMapper mapper, OpenCodeClient openCode,
                                     ModelTokenUsageProjectionService tokenUsage) {
        this.tasks = tasks;
        this.mapper = mapper;
        this.openCode = openCode;
        this.tokenUsage = tokenUsage;
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
            return new SessionActivity(summary, summary.state(), false, Instant.now().toString(), List.of(), List.of(),
                    "Session 尚未获得可读取的 OpenCode 远端标识或 worktree",
                    resolved.todo().capability(), resolved.todo().todos(), resolved.todo().truncated(),
                    resolved.todo().detail(), tokenUsage.taskUsage(task.id()));
        }
        try {
            OpenCodeClient.OpenCodeSession remote = new OpenCodeClient.OpenCodeSession(summary.externalSessionId(), Path.of(task.worktreePath()));
            OpenCodeClient.SessionStatus status = openCode.sessionStatus(remote);
            OpenCodeClient.SessionTranscript transcript = openCode.sessionTranscript(remote);
            ModelTokenUsageProjectionService.UsageView usage = tokenUsage.observeTask(task.id(), remote.worktree(),
                    remote.id(), transcript.usage(), terminal(summary) || status.completed() || status.failed());
            List<ActivityPart> parts = transcript.parts().stream()
                    .map(part -> new ActivityPart(part.id(), part.type(), part.label(), part.content(), part.status(), part.startedAt()))
                    .toList();
            List<PendingQuestion> questions = interactive(task, summary)
                    ? openCode.pendingQuestions(remote).stream().map(this::question).toList()
                    : List.of();
            return new SessionActivity(summary, status.state(), true, Instant.now().toString(), parts, questions,
                    status.detail(), resolved.todo().capability(), resolved.todo().todos(),
                    resolved.todo().truncated(), resolved.todo().detail(), usage);
        } catch (SessionFailure failure) {
            List<ActivityPart> persisted = persistedOutput(summary, resolved.persistedOutput());
            return new SessionActivity(summary, summary.state(), false, Instant.now().toString(), persisted, List.of(),
                    safe(failure.getMessage()), resolved.todo().capability(), resolved.todo().todos(),
                    resolved.todo().truncated(), resolved.todo().detail(), tokenUsage.taskUsage(task.id()));
        } catch (RuntimeException failure) {
            return new SessionActivity(summary, summary.state(), false, Instant.now().toString(),
                    persistedOutput(summary, resolved.persistedOutput()), List.of(), safe(failure.getMessage()),
                    resolved.todo().capability(), resolved.todo().todos(), resolved.todo().truncated(),
                    resolved.todo().detail(), tokenUsage.taskUsage(task.id()));
        }
    }

    public void reply(String taskId, String key, String questionId, List<List<String>> answers) {
        ResolvedRemote resolved = remote(taskId, key);
        OpenCodeClient.PendingQuestion pending = pending(resolved.remote(), questionId);
        List<List<String>> normalized = validateAnswers(pending, answers);
        try {
            openCode.replyQuestion(resolved.remote(), pending.id(), normalized);
        } catch (SessionFailure failure) {
            throw new ServiceUnavailableException(failure.code(), safe(failure.getMessage()));
        }
    }

    public void reject(String taskId, String key, String questionId) {
        ResolvedRemote resolved = remote(taskId, key);
        OpenCodeClient.PendingQuestion pending = pending(resolved.remote(), questionId);
        try {
            openCode.rejectQuestion(resolved.remote(), pending.id());
        } catch (SessionFailure failure) {
            throw new ServiceUnavailableException(failure.code(), safe(failure.getMessage()));
        }
    }

    private PendingQuestion question(OpenCodeClient.PendingQuestion pending) {
        return new PendingQuestion(pending.id(), pending.questions().stream().map(prompt -> new QuestionPrompt(prompt.question(), prompt.header(),
                prompt.options().stream().map(option -> new QuestionOption(option.label(), option.description())).toList(), prompt.multiple(), prompt.custom())).toList());
    }

    private ResolvedRemote remote(String taskId, String key) {
        TaskRow task = tasks.get(taskId);
        ResolvedSession resolved = resolve(taskId, key);
        SessionSummary summary = resolved.summary();
        if (!interactive(task, summary)) {
            throw new ConflictException("SESSION_QUESTION_NOT_ACTIVE",
                    "Task 或 Session 已离开可回答问题的执行状态");
        }
        if (summary.externalSessionId() == null || summary.externalSessionId().isBlank() || task.worktreePath() == null || task.worktreePath().isBlank()) {
            throw new ConflictException("SESSION_REMOTE_UNAVAILABLE", "Session 尚未获得可回答问题的 OpenCode 远端标识或 worktree");
        }
        return new ResolvedRemote(new OpenCodeClient.OpenCodeSession(summary.externalSessionId(), Path.of(task.worktreePath())));
    }

    private boolean interactive(TaskRow task, SessionSummary summary) {
        boolean activeSession = SessionState.CREATING.name().equals(summary.state())
                || SessionState.RUNNING.name().equals(summary.state());
        if (!activeSession) return false;
        return switch (summary.kind()) {
            case "IMPLEMENTATION" -> TaskState.RUNNING.name().equals(task.state());
            case "JUDGE" -> TaskState.JUDGING.name().equals(task.state());
            default -> false;
        };
    }

    private OpenCodeClient.PendingQuestion pending(OpenCodeClient.OpenCodeSession remote, String questionId) {
        if (questionId == null || questionId.isBlank()) throw new BadRequestException("QUESTION_ID_REQUIRED", "Question id is required");
        try {
            return openCode.pendingQuestions(remote).stream().filter(question -> questionId.equals(question.id())).findFirst()
                    .orElseThrow(() -> new NotFoundException("Pending question not found for this Session: " + questionId));
        } catch (SessionFailure failure) {
            throw new ServiceUnavailableException(failure.code(), safe(failure.getMessage()));
        }
    }

    private List<List<String>> validateAnswers(OpenCodeClient.PendingQuestion pending, List<List<String>> answers) {
        if (answers == null || answers.size() != pending.questions().size()) {
            throw new BadRequestException("QUESTION_ANSWERS_INVALID", "Answers must contain one entry for every question");
        }
        List<List<String>> result = new ArrayList<>();
        for (int index = 0; index < pending.questions().size(); index++) {
            OpenCodeClient.QuestionPrompt prompt = pending.questions().get(index);
            List<String> answer = answers.get(index);
            if (answer == null) answer = List.of();
            List<String> normalized = answer.stream().filter(value -> value != null && !value.isBlank()).map(String::trim).distinct().toList();
            if (normalized.isEmpty()) throw new BadRequestException("QUESTION_ANSWER_REQUIRED", "Every question requires an answer");
            if (!prompt.multiple() && normalized.size() > 1) {
                throw new BadRequestException("QUESTION_ANSWER_MULTIPLE_FORBIDDEN", "This question accepts only one answer");
            }
            if (!prompt.custom()) {
                List<String> labels = prompt.options().stream().map(OpenCodeClient.QuestionOption::label).toList();
                if (!labels.containsAll(normalized)) throw new BadRequestException("QUESTION_CUSTOM_ANSWER_FORBIDDEN", "This question only accepts listed options");
            }
            result.add(normalized);
        }
        return List.copyOf(result);
    }

    private ResolvedSession resolve(String taskId, String key) {
        if (key != null && key.startsWith("execution:")) {
            String id = key.substring("execution:".length());
            ExecutionSessionRow row = mapper.findSession(id).orElseThrow(() -> new NotFoundException("Task Session not found: " + id));
            if (!taskId.equals(row.taskId())) throw new BadRequestException("SESSION_TASK_MISMATCH", "Session does not belong to Task " + taskId);
            return new ResolvedSession(summary(row), null, todoProjection(row));
        }
        if (key != null && key.startsWith("judge:")) {
            String id = key.substring("judge:".length());
            JudgeRunRow row = mapper.findJudgeRun(id).orElseThrow(() -> new NotFoundException("Judge Session not found: " + id));
            if (!taskId.equals(row.taskId())) throw new BadRequestException("SESSION_TASK_MISMATCH", "Judge Session does not belong to Task " + taskId);
            return new ResolvedSession(summary(row), row.rawOutput(), TodoProjection.none());
        }
        throw new BadRequestException("SESSION_KEY_INVALID", "Session key must start with execution: or judge:");
    }

    private SessionSummary summary(ExecutionSessionRow row) {
        StageRow stage = mapper.findStage(row.stageId()).orElse(null);
        return new SessionSummary("execution:" + row.id(), "IMPLEMENTATION", "Implementation Session", row.id(), row.externalSessionId(),
                row.state(), row.stageId(), stage == null ? null : stage.ordinal() + 1, stage == null ? null : stage.objective(),
                row.attemptId(), row.createdAt(), row.endedAt());
    }

    private SessionSummary summary(JudgeRunRow row) {
        return new SessionSummary("judge:" + row.id(), "JUDGE", row.role() + " Judge", row.id(), row.externalSessionId(),
                row.state(), null, null, null, row.attemptId(), row.createdAt(), row.endedAt());
    }

    private List<ActivityPart> persistedOutput(SessionSummary summary, String output) {
        return output == null || output.isBlank() ? List.of()
                : List.of(new ActivityPart(summary.key() + ":persisted", "OUTPUT", "已持久化模型输出", output, "COMPLETED",
                        summary.endedAt() == null ? summary.createdAt() : summary.endedAt()));
    }

    private TodoProjection todoProjection(ExecutionSessionRow session) {
        List<SessionTodoRow> rows = mapper.listSessionTodos(session.id());
        boolean truncated = rows.stream().anyMatch(row -> row.payloadJson() != null
                && row.payloadJson().contains("\"projectionTruncated\":true"));
        String capability = session.todoCapability() == null ? TodoCapability.UNKNOWN.name() : session.todoCapability();
        String detail = truncated ? "OpenCode Todo 投影已按安全上限截断"
                : TodoCapability.UNAVAILABLE.name().equals(capability) ? "当前工作区未暴露 todowrite"
                : TodoCapability.UNKNOWN.name().equals(capability) ? "无法确认当前工作区的 todowrite 能力" : null;
        List<TodoActivity> todos = rows.stream().map(row -> new TodoActivity(row.externalTodoId(), row.content(),
                row.status(), row.priority(), row.ordinal())).toList();
        return new TodoProjection(capability, todos, truncated, detail);
    }

    private String safe(String value) {
        if (value == null || value.isBlank()) return "OpenCode Session 暂时不可读取";
        return value.length() <= 2_000 ? value : value.substring(0, 2_000);
    }

    private boolean terminal(SessionSummary summary) {
        try {
            return "JUDGE".equals(summary.kind())
                    ? JudgeRunState.valueOf(summary.state()).terminal()
                    : SessionState.valueOf(summary.state()).terminal();
        } catch (RuntimeException unknown) {
            return false;
        }
    }

    public record SessionSummary(String key, String kind, String label, String localSessionId, String externalSessionId,
                                 String state, String stageId, Integer stageOrdinal, String stageObjective,
                                 String attemptId, String createdAt, String endedAt) { }
    public record ActivityPart(String id, String type, String label, String content, String status, String startedAt) { }
    public record PendingQuestion(String id, List<QuestionPrompt> questions) { }
    public record QuestionPrompt(String question, String header, List<QuestionOption> options, boolean multiple, boolean custom) { }
    public record QuestionOption(String label, String description) { }
    public record SessionActivity(SessionSummary session, String remoteState, boolean live, String observedAt,
                                  List<ActivityPart> parts, List<PendingQuestion> pendingQuestions, String detail,
                                  String todoCapability, List<TodoActivity> todos, boolean todoTruncated,
                                  String todoDetail, ModelTokenUsageProjectionService.UsageView usage) { }
    public record TodoActivity(String id, String content, String status, String priority, int ordinal) { }
    private record TodoProjection(String capability, List<TodoActivity> todos, boolean truncated, String detail) {
        private static TodoProjection none() { return new TodoProjection(TodoCapability.UNKNOWN.name(), List.of(), false, null); }
    }
    private record ResolvedSession(SessionSummary summary, String persistedOutput, TodoProjection todo) { }
    private record ResolvedRemote(OpenCodeClient.OpenCodeSession remote) { }
}
