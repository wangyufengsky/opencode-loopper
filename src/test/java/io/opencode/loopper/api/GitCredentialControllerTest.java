package io.opencode.loopper.api;

import io.opencode.loopper.service.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class GitCredentialControllerTest {
    private final GitCredentialService credentials = mock(GitCredentialService.class);
    private final GitCredentialProbe probe = mock(GitCredentialProbe.class);
    private final org.springframework.test.web.servlet.MockMvc mvc = MockMvcBuilders
            .standaloneSetup(new GitCredentialController(credentials, probe)).setControllerAdvice(new ApiExceptionHandler()).build();
    @Test void requiresLocalUiBeforeSavingOrTestingAndNeverReturnsSecrets() throws Exception {
        String body = "{\"mode\":\"CUSTOM\",\"version\":0,\"secret\":\"fixture\"}";
        mvc.perform(put("/api/git-credentials").contentType("application/json").content(body)).andExpect(status().isBadRequest());
        mvc.perform(post("/api/git-credentials/test").contentType("application/json").content(body)).andExpect(status().isBadRequest());
        verifyNoInteractions(credentials, probe);
        when(credentials.get("project")).thenReturn(new GitCredentialService.View("INHERIT", "https://gitlab.example", "user", "TOKEN", true, "GLOBAL", 0, null));
        mvc.perform(get("/api/git-credentials").param("projectId", "project")).andExpect(status().isOk())
                .andExpect(jsonPath("$.source").value("GLOBAL")).andExpect(jsonPath("$.secret").doesNotExist()).andExpect(jsonPath("$.secretRef").doesNotExist());
        when(probe.test(eq("project"), any())).thenReturn(new GitCredentialProbe.Result(true, "连接成功"));
        mvc.perform(post("/api/git-credentials/test").param("projectId", "project").header("X-Loopper-Local-UI", "1")
                .contentType("application/json").content(body)).andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true));
        verify(credentials, never()).save(any(), any());
    }
}
