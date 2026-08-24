package io.opencode.loopper.api;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.opencode.loopper.persistence.LoopperMapper;
import io.opencode.loopper.persistence.AttemptRow;
import io.opencode.loopper.persistence.ExecutionSessionRow;
import io.opencode.loopper.persistence.TaskRow;
import io.opencode.loopper.runtime.GitWorktreeManager;
import io.opencode.loopper.service.LoopDraftService;
import io.opencode.loopper.service.LocalSyncConflictService;
import io.opencode.loopper.service.TaskEventHub;
import io.opencode.loopper.service.TaskPublicationService;
import io.opencode.loopper.service.TaskService;
import io.opencode.loopper.service.TaskDesignOriginService;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.ObjectMapper;

class TaskControllerArchiveTest {
    private final TaskService tasks = mock(TaskService.class);
    private final LoopperMapper mapper = mock(LoopperMapper.class);
    private final TaskController controller = new TaskController(tasks, mapper, mock(TaskEventHub.class),
            mock(ObjectMapper.class), mock(LoopDraftService.class), mock(TaskPublicationService.class),
            mock(LocalSyncConflictService.class), mock(TaskDesignOriginService.class));
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new ApiExceptionHandler()).build();

    @BeforeEach
    void defaultLoopRetryProjection() {
        when(tasks.loopRetryStatus(anyString())).thenReturn(new TaskService.LoopRetryStatus(null, false));
    }

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
    void deletesArchivedHistoryOnlyThroughTheLocalUiContract() throws Exception {
        mvc.perform(delete("/api/tasks/task-1").header("X-Loopper-Local-UI", "1"))
                .andExpect(status().isNoContent());
        verify(tasks).deleteArchived("task-1");

        mvc.perform(delete("/api/tasks/task-1"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void retriesJudgesOnlyThroughTheLocalUiContract() throws Exception {
        TaskRow task = new TaskRow("task-1", "project-1", null, "Review", "JUDGING",
                "/tmp/project", "DIRECT", null, "2026-08-05T00:00:00Z", "2026-08-05T00:01:00Z", 1);
        when(tasks.retryJudges("task-1")).thenReturn(task);
        when(tasks.archived("task-1")).thenReturn(false);
        when(tasks.attempts("task-1")).thenReturn(List.of());
        when(tasks.stages("task-1")).thenReturn(List.of());
        when(tasks.errors("task-1")).thenReturn(List.of());
        when(tasks.judges("task-1")).thenReturn(List.of());
        when(tasks.artifacts("task-1")).thenReturn(List.of());
        when(mapper.findProject("project-1")).thenReturn(Optional.empty());

        mvc.perform(post("/api/tasks/task-1/judges/retry").header("X-Loopper-Local-UI", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("JUDGING"));
        verify(tasks).retryJudges("task-1");

        mvc.perform(post("/api/tasks/task-1/judges/retry"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void retriesAWaitingLoopOnlyThroughTheLocalUiContract() throws Exception {
        TaskRow task = new TaskRow("task-loop", "project-1", null, "Stagnant", "RUNNING",
                "/tmp/project", "DIRECT", null, "2026-08-05T00:00:00Z", "2026-08-05T00:01:00Z", 1);
        when(tasks.retryWaitingLoop("task-loop")).thenReturn(task);
        when(tasks.archived("task-loop")).thenReturn(false);
        when(tasks.attempts("task-loop")).thenReturn(List.of());
        when(tasks.stages("task-loop")).thenReturn(List.of());
        when(tasks.errors("task-loop")).thenReturn(List.of());
        when(tasks.judges("task-loop")).thenReturn(List.of());
        when(tasks.artifacts("task-loop")).thenReturn(List.of());
        when(mapper.findProject("project-1")).thenReturn(Optional.empty());

        mvc.perform(post("/api/tasks/task-loop/loop/retry").header("X-Loopper-Local-UI", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RUNNING"))
                .andExpect(jsonPath("$.waitingReasonCode").doesNotExist())
                .andExpect(jsonPath("$.loopRetryAvailable").value(false));
        verify(tasks).retryWaitingLoop("task-loop");

        mvc.perform(post("/api/tasks/task-loop/loop/retry"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void returnsPersistedQueueAndLeaseStatus() throws Exception {
        when(tasks.queueStatus("task-queued")).thenReturn(
                new FeatureContracts.QueueStatusDto("task-queued", "QUEUED", 2L, "RELEASE_PENDING", "abc123",
                        "task-holder", "已取消的持有任务", "CANCELLED", true,
                        "SESSION_WRITER_UNCONFIRMED", true));

        mvc.perform(get("/api/tasks/task-queued/queue"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.taskId").value("task-queued"))
                .andExpect(jsonPath("$.state").value("QUEUED"))
                .andExpect(jsonPath("$.queuePosition").value(2))
                .andExpect(jsonPath("$.leaseState").value("RELEASE_PENDING"))
                .andExpect(jsonPath("$.rootFingerprint").value("abc123"))
                .andExpect(jsonPath("$.holderTaskId").value("task-holder"))
                .andExpect(jsonPath("$.holderTaskTitle").value("已取消的持有任务"))
                .andExpect(jsonPath("$.holderTaskState").value("CANCELLED"))
                .andExpect(jsonPath("$.holderArchived").value(true))
                .andExpect(jsonPath("$.releaseReason").value("SESSION_WRITER_UNCONFIRMED"))
                .andExpect(jsonPath("$.reconcileAvailable").value(true));
    }

    @Test
    void reconcilesAQueuedWorkspaceOnlyThroughTheLocalUiContract() throws Exception {
        FeatureContracts.QueueStatusDto status = new FeatureContracts.QueueStatusDto(
                "task-queued", "ADMITTED", null, "HELD", "abc123",
                "task-queued", "排队任务", "QUEUED", false, null, false);
        when(tasks.reconcileQueue("task-queued")).thenReturn(status);

        mvc.perform(post("/api/tasks/task-queued/queue/reconcile")
                        .header("X-Loopper-Local-UI", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("ADMITTED"));
        verify(tasks).reconcileQueue("task-queued");

        mvc.perform(post("/api/tasks/task-queued/queue/reconcile"))
                .andExpect(status().isBadRequest());
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

    @Test
    void exposesDirtyFilesAndResolvesThemOnlyThroughTheLocalUiContract() throws Exception {
        GitWorktreeManager.DirtyWorkspace dirty = new GitWorktreeManager.DirtyWorkspace(
                "main", "abc123", "snapshot-1", List.of(
                new GitWorktreeManager.DirtyFile("README.md", null, " ", "M", false),
                new GitWorktreeManager.DirtyFile("notes.txt", null, "?", "?", true)));
        TaskRow ready = new TaskRow("task-dirty", "project-1", null, "Dirty", "READY",
                "/tmp/project", "loopper/Dirty", "abc123", "2026-08-12T00:00:00Z", "2026-08-12T00:01:00Z", 2);
        GitWorktreeManager.DirtyWorkspace clean = new GitWorktreeManager.DirtyWorkspace(
                "loopper/Dirty", "def456", "snapshot-2", List.of());
        when(tasks.workspaceDirtyStatus("task-dirty")).thenReturn(dirty);
        when(tasks.resolveDirtyWorkspace(org.mockito.ArgumentMatchers.eq("task-dirty"),
                org.mockito.ArgumentMatchers.eq("snapshot-1"), org.mockito.ArgumentMatchers.anyList(),
                org.mockito.ArgumentMatchers.eq("save local work")))
                .thenReturn(new TaskService.WorkspaceDirtyResolution(ready, clean));
        when(tasks.archived(ready.id())).thenReturn(false);
        when(tasks.attempts(ready.id())).thenReturn(List.of());
        when(tasks.stages(ready.id())).thenReturn(List.of());
        when(tasks.errors(ready.id())).thenReturn(List.of());
        when(tasks.judges(ready.id())).thenReturn(List.of());
        when(tasks.artifacts(ready.id())).thenReturn(List.of());
        when(mapper.findProject(ready.projectId())).thenReturn(Optional.empty());

        mvc.perform(get("/api/tasks/task-dirty/workspace-dirty"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.snapshotId").value("snapshot-1"))
                .andExpect(jsonPath("$.files[0].path").value("README.md"))
                .andExpect(jsonPath("$.files[1].untracked").value(true));

        String body = """
                {"snapshotId":"snapshot-1","commitMessage":"save local work","resolutions":[
                  {"path":"README.md","action":"COMMIT"},{"path":"notes.txt","action":"STASH"}
                ]}
                """;
        mvc.perform(post("/api/tasks/task-dirty/workspace-dirty/resolve")
                        .header("X-Loopper-Local-UI", "1")
                        .contentType("application/json").content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.task.status").value("READY"))
                .andExpect(jsonPath("$.workspace.clean").value(true));
        mvc.perform(post("/api/tasks/task-dirty/workspace-dirty/resolve")
                        .contentType("application/json").content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void cancellingDirtyWorkspaceCancelsTheTaskOnlyThroughTheLocalUiContract() throws Exception {
        TaskRow failed = new TaskRow("task-dirty", "project-1", null, "Dirty", "CANCELLED",
                "/tmp/project", null, null, "2026-08-12T00:00:00Z", "2026-08-12T00:01:00Z", 2);
        when(tasks.cancelDirtyWorkspace(failed.id())).thenReturn(failed);
        when(tasks.archived(failed.id())).thenReturn(false);
        when(tasks.attempts(failed.id())).thenReturn(List.of());
        when(tasks.stages(failed.id())).thenReturn(List.of());
        when(tasks.errors(failed.id())).thenReturn(List.of());
        when(tasks.judges(failed.id())).thenReturn(List.of());
        when(tasks.artifacts(failed.id())).thenReturn(List.of());
        when(mapper.findProject(failed.projectId())).thenReturn(Optional.empty());

        mvc.perform(post("/api/tasks/task-dirty/workspace-dirty/cancel")
                        .header("X-Loopper-Local-UI", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
        mvc.perform(post("/api/tasks/task-dirty/workspace-dirty/cancel"))
                .andExpect(status().isBadRequest());
    }
}
