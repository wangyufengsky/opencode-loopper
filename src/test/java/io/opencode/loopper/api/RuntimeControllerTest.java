package io.opencode.loopper.api;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.opencode.loopper.runtime.OpenCodeRuntimeManager;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class RuntimeControllerTest {
    private final OpenCodeRuntimeManager runtime = mock(OpenCodeRuntimeManager.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new RuntimeController(runtime))
            .setControllerAdvice(new ApiExceptionHandler()).build();

    @Test
    void explicitStartRequiresLocalUiAndReturnsOnlyTheCheckedRuntimeSnapshot() throws Exception {
        when(runtime.manuallyStartable()).thenReturn(true);
        when(runtime.startAndCheck()).thenReturn(new OpenCodeRuntimeManager.RuntimeSnapshot(
                "AVAILABLE", "1.18.16", true, 6400L, "http://127.0.0.1:34020", "",
                Instant.parse("2026-08-12T06:30:00Z"), null));

        mvc.perform(post("/api/runtime/opencode/start"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("LOCAL_UI_HEADER_REQUIRED"));

        mvc.perform(post("/api/runtime/opencode/start").header("X-Loopper-Local-UI", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("AVAILABLE"))
                .andExpect(jsonPath("$.managed").value(true))
                .andExpect(jsonPath("$.pid").value(6400))
                .andExpect(jsonPath("$.endpoint").value("http://127.0.0.1:34020"))
                .andExpect(jsonPath("$.startupFailure").doesNotExist());
        verify(runtime).startAndCheck();
    }
}
