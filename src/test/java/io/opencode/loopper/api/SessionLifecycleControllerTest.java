package io.opencode.loopper.api;

import io.opencode.loopper.service.SessionLifecycleService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SessionLifecycleControllerTest {
    private final SessionLifecycleService lifecycle = mock(SessionLifecycleService.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new SessionLifecycleController(lifecycle))
            .setControllerAdvice(new ApiExceptionHandler()).build();

    @Test
    void onlyLocalUiCanMutateAndReadsReturnPersistedSnapshots() throws Exception {
        when(lifecycle.todos("task-1", "session-1")).thenReturn(List.of(new SessionLifecycleService.TodoDto("local", "remote", "真实 todo", "OPEN", "HIGH", 1, "now")));
        when(lifecycle.checkpoint("task-1", "session-1", "fake-message"))
                .thenReturn(new SessionLifecycleService.CheckpointDto("checkpoint", "task-1", "session-1", "attempt", "fake-message", "a".repeat(64), "now"));
        mvc.perform(post("/api/tasks/task-1/sessions/session-1/checkpoints").contentType(MediaType.APPLICATION_JSON).content("{\"externalMessageId\":\"fake-message\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errorCode").value("LOCAL_UI_HEADER_REQUIRED"));
        mvc.perform(post("/api/tasks/task-1/sessions/session-1/checkpoints").header("X-Loopper-Local-UI", "1")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"externalMessageId\":\"fake-message\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.contentSha256").value("a".repeat(64)));
        mvc.perform(get("/api/tasks/task-1/sessions/session-1/todos"))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].content").value("真实 todo"));
        verify(lifecycle).checkpoint("task-1", "session-1", "fake-message");
    }
}
