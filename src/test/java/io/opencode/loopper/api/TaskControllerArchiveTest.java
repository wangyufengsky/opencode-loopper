package io.opencode.loopper.api;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.opencode.loopper.persistence.LoopperMapper;
import io.opencode.loopper.persistence.AttemptRow;
import io.opencode.loopper.persistence.ExecutionSessionRow;
import io.opencode.loopper.persistence.TaskRow;
import io.opencode.loopper.service.LoopDraftService;
import io.opencode.loopper.service.TaskEventHub;
import io.opencode.loopper.service.TaskPublicationService;
import io.opencode.loopper.service.TaskService;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.ObjectMapper;

class TaskControllerArchiveTest {
    private final TaskService tasks = mock(TaskService.class);
    private final LoopperMapper mapper = mock(LoopperMapper.class);
    private final TaskController controller = new TaskController(tasks, mapper, mock(TaskEventHub.class),
            mock(ObjectMapper.class), mock(LoopDraftService.class), mock(TaskPublicationService.class));
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new ApiExceptionHandler()).build();

    @Test
    void archivesOnlyThroughTheLocalUiContractAndReturnsRecoverableMetadata() throws Exception {
        TaskRow task = new TaskRow("task-1", "project-1", null, "Finished", "CANCELLED",
                "/tmp/project", "DIRECT", null, "2026-08-05T00:00:00Z", "2026-08-05T00:01:00Z", 1);
        when(tasks.archive("task-1")).thenReturn(task);
        when(tasks.archived("task-1")).thenReturn(true);
        when(tasks.attempts("task-1")).thenReturn(List.of());
        when(tasks.stages("task-1")).thenReturn(List.of());
        when(tasks.errors("task-1")).thenReturn(List.of());
        when(tasks.judges("task-1")).thenReturn(List.of());
        when(tasks.artifacts("task-1")).thenReturn(List.of());
        when(mapper.findProject("project-1")).thenReturn(Optional.empty());

        mvc.perform(put("/api/tasks/task-1/archive").header("X-Loopper-Local-UI", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("task-1"))
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.archived").value(true));

        mvc.perform(put("/api/tasks/task-1/archive"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void returnsPersistedQueueAndLeaseStatus() throws Exception {
        when(tasks.queueStatus("task-queued")).thenReturn(
                new FeatureContracts.QueueStatusDto("task-queued", "QUEUED", 2L, "RELEASE_PENDING", "abc123"));

        mvc.perform(get("/api/tasks/task-queued/queue"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.taskId").value("task-queued"))
                .andExpect(jsonPath("$.state").value("QUEUED"))
                .andExpect(jsonPath("$.queuePosition").value(2))
                .andExpect(jsonPath("$.leaseState").value("RELEASE_PENDING"))
                .andExpect(jsonPath("$.rootFingerprint").value("abc123"));
    }

    @Test
    void taskAttemptsExposeTheLocalExecutionSessionId() throws Exception {
        TaskRow task = new TaskRow("task-session", "project-session", null, "Session", "PAUSED",
                "/tmp/project", "feature/session", "baseline", "2026-08-05T00:00:00Z", "2026-08-05T00:01:00Z", 1);
        AttemptRow attempt = new AttemptRow("attempt-local", task.id(), "stage-local", 1, "SUCCEEDED", null,
                "done", task.createdAt(), task.updatedAt(), 0);
        ExecutionSessionRow session = new ExecutionSessionRow("session-local", task.id(), attempt.stageId(), attempt.id(),
                "session-external", "COMPLETED", task.createdAt(), task.updatedAt(), 0);
        when(tasks.get(task.id())).thenReturn(task);
        when(tasks.archived(task.id())).thenReturn(false);
        when(tasks.attempts(task.id())).thenReturn(List.of(attempt));
        when(tasks.stages(task.id())).thenReturn(List.of());
        when(tasks.errors(task.id())).thenReturn(List.of());
        when(tasks.judges(task.id())).thenReturn(List.of());
        when(tasks.artifacts(task.id())).thenReturn(List.of());
        when(tasks.verifications(attempt.id())).thenReturn(List.of());
        when(mapper.latestSessionForAttempt(attempt.id())).thenReturn(Optional.of(session));
        when(mapper.findProject(task.projectId())).thenReturn(Optional.empty());

        mvc.perform(get("/api/tasks/{id}", task.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.attempts[0].sessionId").value("session-local"));
    }
}
