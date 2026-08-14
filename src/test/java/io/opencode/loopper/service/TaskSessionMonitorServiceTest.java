package io.opencode.loopper.service;

import io.opencode.loopper.persistence.ExecutionSessionRow;
import io.opencode.loopper.persistence.LoopperMapper;
import io.opencode.loopper.persistence.StageRow;
import io.opencode.loopper.persistence.SessionTodoRow;
import io.opencode.loopper.persistence.TaskRow;
import io.opencode.loopper.runtime.OpenCodeClient;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TaskSessionMonitorServiceTest {
    private final TaskService tasks = mock(TaskService.class);
    private final LoopperMapper mapper = mock(LoopperMapper.class);
    private final OpenCodeClient openCode = mock(OpenCodeClient.class);
    private final TaskSessionMonitorService monitor = new TaskSessionMonitorService(tasks, mapper, openCode);

    @Test
    void returnsProviderExposedLiveThinkingAndOutputForTheSelectedTaskSession() {
        TaskRow task = new TaskRow("task-1", "project-1", "draft-1", "Monitor", "RUNNING",
                "/tmp/worktree", "loopper/task-1", "abc", "2026-08-04T08:00:00Z", "2026-08-04T08:00:00Z", 0);
        ExecutionSessionRow session = new ExecutionSessionRow("local-1", task.id(), "stage-1", "attempt-1",
                "remote-1", "RUNNING", "2026-08-04T08:01:00Z", null, 0, "AVAILABLE");
        when(tasks.get(task.id())).thenReturn(task);
        when(mapper.findSession(session.id())).thenReturn(Optional.of(session));
        when(mapper.findStage(session.stageId())).thenReturn(Optional.of(new StageRow(session.stageId(), task.id(), 0,
                "实现动态会话监控并完成本阶段验证", "[]", "[]", "[]", "[]", "RUNNING", task.createdAt(), task.updatedAt(), 0)));
        when(mapper.listSessionTodos(session.id())).thenReturn(List.of(new SessionTodoRow("todo-local", session.id(),
                "todo-v2:abc:1", "实现并验证 Todo 投影", "IN_PROGRESS", "HIGH", 0,
                "{\"rawStatus\":\"in_progress\"}", task.updatedAt(), 0)));
        OpenCodeClient.OpenCodeSession remote = new OpenCodeClient.OpenCodeSession(session.externalSessionId(), Path.of(task.worktreePath()));
        when(openCode.sessionStatus(remote)).thenReturn(new OpenCodeClient.SessionStatus("busy", "editing"));
        when(openCode.sessionTranscript(remote)).thenReturn(new OpenCodeClient.SessionTranscript(List.of(
                new OpenCodeClient.SessionPart("reason-1", "THINKING", "Thinking", "Inspecting files", "running", "2026-08-04T08:01:01Z"),
                new OpenCodeClient.SessionPart("text-1", "OUTPUT", "模型输出", "Implementing now", null))));
        when(openCode.pendingQuestions(remote)).thenReturn(List.of());

        TaskSessionMonitorService.SessionActivity activity = monitor.activity(task.id(), "execution:" + session.id());

        assertThat(activity.live()).isTrue();
        assertThat(activity.remoteState()).isEqualTo("busy");
        assertThat(activity.parts()).extracting(TaskSessionMonitorService.ActivityPart::type)
                .containsExactly("THINKING", "OUTPUT");
        assertThat(activity.parts().get(0).content()).isEqualTo("Inspecting files");
        assertThat(activity.parts().get(0).startedAt()).isEqualTo("2026-08-04T08:01:01Z");
        assertThat(activity.session().stageOrdinal()).isEqualTo(1);
        assertThat(activity.session().stageObjective()).isEqualTo("实现动态会话监控并完成本阶段验证");
        assertThat(activity.todoCapability()).isEqualTo("AVAILABLE");
        assertThat(activity.todos()).singleElement().satisfies(todo -> {
            assertThat(todo.content()).isEqualTo("实现并验证 Todo 投影");
            assertThat(todo.status()).isEqualTo("IN_PROGRESS");
        });
    }

    @Test
    void exposesPendingQuestionsAndForwardsValidatedAnswersToTheSameRemoteSession() {
        TaskRow task = new TaskRow("task-1", "project-1", "draft-1", "Monitor", "RUNNING",
                "/tmp/worktree", "loopper/task-1", "abc", "2026-08-04T08:00:00Z", "2026-08-04T08:00:00Z", 0);
        ExecutionSessionRow session = new ExecutionSessionRow("local-1", task.id(), "stage-1", "attempt-1",
                "remote-1", "RUNNING", "2026-08-04T08:01:00Z", null, 0);
        when(tasks.get(task.id())).thenReturn(task);
        when(mapper.findSession(session.id())).thenReturn(Optional.of(session));
        OpenCodeClient.OpenCodeSession remote = new OpenCodeClient.OpenCodeSession(session.externalSessionId(), Path.of(task.worktreePath()));
        OpenCodeClient.PendingQuestion pending = new OpenCodeClient.PendingQuestion("que-1", "remote-1", List.of(
                new OpenCodeClient.QuestionPrompt("Choose", "Build", List.of(
                        new OpenCodeClient.QuestionOption("Option A", "Use A"),
                        new OpenCodeClient.QuestionOption("Option B", "Use B")), false, false)));
        when(openCode.sessionStatus(remote)).thenReturn(new OpenCodeClient.SessionStatus("busy"));
        when(openCode.sessionTranscript(remote)).thenReturn(new OpenCodeClient.SessionTranscript(List.of()));
        when(openCode.pendingQuestions(remote)).thenReturn(List.of(pending));

        TaskSessionMonitorService.SessionActivity activity = monitor.activity(task.id(), "execution:" + session.id());
        monitor.reply(task.id(), "execution:" + session.id(), "que-1", List.of(List.of("Option A")));

        assertThat(activity.pendingQuestions()).hasSize(1);
        assertThat(activity.pendingQuestions().getFirst().questions().getFirst().question()).isEqualTo("Choose");
        verify(openCode).replyQuestion(remote, "que-1", List.of(List.of("Option A")));
    }

    @Test
    void hidesAndRejectsQuestionsAfterTheTaskHasReachedATerminalState() {
        TaskRow task = new TaskRow("task-1", "project-1", "draft-1", "Monitor", "CANCELLED",
                "/tmp/worktree", "loopper/task-1", "abc", "2026-08-04T08:00:00Z", "2026-08-04T08:02:00Z", 0);
        ExecutionSessionRow session = new ExecutionSessionRow("local-1", task.id(), "stage-1", "attempt-1",
                "remote-1", "RUNNING", "2026-08-04T08:01:00Z", null, 0);
        when(tasks.get(task.id())).thenReturn(task);
        when(mapper.findSession(session.id())).thenReturn(Optional.of(session));
        OpenCodeClient.OpenCodeSession remote = new OpenCodeClient.OpenCodeSession(session.externalSessionId(), Path.of(task.worktreePath()));
        when(openCode.sessionStatus(remote)).thenReturn(new OpenCodeClient.SessionStatus("busy"));
        when(openCode.sessionTranscript(remote)).thenReturn(new OpenCodeClient.SessionTranscript(List.of()));

        TaskSessionMonitorService.SessionActivity activity = monitor.activity(task.id(), "execution:" + session.id());

        assertThat(activity.pendingQuestions()).isEmpty();
        assertThatThrownBy(() -> monitor.reply(task.id(), "execution:" + session.id(), "que-1", List.of(List.of("Option A"))))
                .isInstanceOfSatisfying(ConflictException.class,
                        failure -> assertThat(failure.code()).isEqualTo("SESSION_QUESTION_NOT_ACTIVE"));
        assertThatThrownBy(() -> monitor.reject(task.id(), "execution:" + session.id(), "que-1"))
                .isInstanceOfSatisfying(ConflictException.class,
                        failure -> assertThat(failure.code()).isEqualTo("SESSION_QUESTION_NOT_ACTIVE"));
        verify(openCode, never()).pendingQuestions(remote);
        verify(openCode, never()).replyQuestion(remote, "que-1", List.of(List.of("Option A")));
        verify(openCode, never()).rejectQuestion(remote, "que-1");
    }
}
