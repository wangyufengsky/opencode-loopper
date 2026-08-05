package io.opencode.loopper.service;

import io.opencode.loopper.persistence.ExecutionSessionRow;
import io.opencode.loopper.persistence.LoopperMapper;
import io.opencode.loopper.persistence.TaskRow;
import io.opencode.loopper.runtime.OpenCodeClient;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
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
                "remote-1", "RUNNING", "2026-08-04T08:01:00Z", null, 0);
        when(tasks.get(task.id())).thenReturn(task);
        when(mapper.findSession(session.id())).thenReturn(Optional.of(session));
        OpenCodeClient.OpenCodeSession remote = new OpenCodeClient.OpenCodeSession(session.externalSessionId(), Path.of(task.worktreePath()));
        when(openCode.sessionStatus(remote)).thenReturn(new OpenCodeClient.SessionStatus("busy", "editing"));
        when(openCode.sessionTranscript(remote)).thenReturn(new OpenCodeClient.SessionTranscript(List.of(
                new OpenCodeClient.SessionPart("reason-1", "THINKING", "Thinking", "Inspecting files", "running", "2026-08-04T08:01:01Z"),
                new OpenCodeClient.SessionPart("text-1", "OUTPUT", "模型输出", "Implementing now", null))));

        TaskSessionMonitorService.SessionActivity activity = monitor.activity(task.id(), "execution:" + session.id());

        assertThat(activity.live()).isTrue();
        assertThat(activity.remoteState()).isEqualTo("busy");
        assertThat(activity.parts()).extracting(TaskSessionMonitorService.ActivityPart::type)
                .containsExactly("THINKING", "OUTPUT");
        assertThat(activity.parts().get(0).content()).isEqualTo("Inspecting files");
        assertThat(activity.parts().get(0).startedAt()).isEqualTo("2026-08-04T08:01:01Z");
    }
}
