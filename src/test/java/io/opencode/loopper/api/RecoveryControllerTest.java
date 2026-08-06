package io.opencode.loopper.api;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.opencode.loopper.domain.RecoveryMode;
import io.opencode.loopper.service.RecoveryService;
import io.opencode.loopper.service.ConflictException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class RecoveryControllerTest {
    private final RecoveryService recoveries = mock(RecoveryService.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new RecoveryController(recoveries))
            .setControllerAdvice(new ApiExceptionHandler()).build();

    @Test
    void writesRequireTheLocalUiHeaderAndReadsReturnPersistedLineage() throws Exception {
        FeatureContracts.RecoveryDto dto = new FeatureContracts.RecoveryDto("child-1", "parent-1", RecoveryMode.ALL_STAGES,
                "stage-1", "workspace-fingerprint", true);
        when(recoveries.create("parent-1", RecoveryMode.ALL_STAGES)).thenReturn(dto);
        when(recoveries.list("parent-1")).thenReturn(List.of(dto));

        mvc.perform(post("/api/tasks/parent-1/recoveries").contentType(MediaType.APPLICATION_JSON).content("{\"mode\":\"ALL_STAGES\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("LOCAL_UI_HEADER_REQUIRED"));
        mvc.perform(post("/api/tasks/parent-1/recoveries").header("X-Loopper-Local-UI", "1")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"mode\":\"ALL_STAGES\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.taskId").value("child-1"))
                .andExpect(jsonPath("$.writableSession").value(true));
        mvc.perform(get("/api/tasks/parent-1/recoveries"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].parentTaskId").value("parent-1"));
        verify(recoveries).create("parent-1", RecoveryMode.ALL_STAGES);
    }

    @Test
    void mapsDirectWorkspaceFingerprintMismatchToConflict() throws Exception {
        when(recoveries.create("parent-1", RecoveryMode.FROM_FAILED_STAGE)).thenThrow(new ConflictException(
                "RECOVERY_WORKSPACE_FINGERPRINT_MISMATCH", "Direct workspace fingerprint changed"));

        mvc.perform(post("/api/tasks/parent-1/recoveries").header("X-Loopper-Local-UI", "1")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"mode\":\"FROM_FAILED_STAGE\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("RECOVERY_WORKSPACE_FINGERPRINT_MISMATCH"));
    }
}
