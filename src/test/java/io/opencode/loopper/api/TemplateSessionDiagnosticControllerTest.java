package io.opencode.loopper.api;

import io.opencode.loopper.service.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class TemplateSessionDiagnosticControllerTest {
    private final TemplateSessionDiagnostics diagnostics = mock(TemplateSessionDiagnostics.class);
    private final TemplateBatchRecoveryStore recovery = mock(TemplateBatchRecoveryStore.class);
    private final TemplateTaskCoordinator coordinator = mock(TemplateTaskCoordinator.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new TemplateSessionDiagnosticController(diagnostics, recovery, coordinator))
            .setControllerAdvice(new ApiExceptionHandler()).build();

    @Test void recoveryRequiresLocalUiBeforeAnyReadOrMutation() throws Exception {
        mvc.perform(post("/api/tasks/t/session-diagnostics/b/recover").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"FINALIZE\",\"expectedVersion\":7,\"commandId\":\"command-1234567890\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errorCode").value("LOCAL_UI_HEADER_REQUIRED"));
        verifyNoInteractions(diagnostics, recovery, coordinator);
    }

    @Test void recoveryUsesExactTaskBatchVersionAndCommandAndDispatchesOnlyAfterIntent() throws Exception {
        mvc.perform(post("/api/tasks/t/session-diagnostics/b/recover").header("X-Loopper-Local-UI", "1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"FINALIZE\",\"expectedVersion\":7,\"commandId\":\"command-1234567890\"}"))
                .andExpect(status().isOk());
        var order = inOrder(diagnostics, recovery, coordinator);
        order.verify(diagnostics).get("t", "b");
        order.verify(recovery).request("t", "b", "FINALIZE", 7, "command-1234567890");
        order.verify(coordinator).dispatch("t");
        order.verify(diagnostics).get("t", "b");
    }

    @Test void foreignBatchCannotDispatchAndFilterCursorAreServerParameters() throws Exception {
        when(diagnostics.get("t", "foreign")).thenThrow(new NotFoundException("批次不属于当前任务"));
        mvc.perform(post("/api/tasks/t/session-diagnostics/foreign/recover").header("X-Loopper-Local-UI", "1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"STOP\",\"expectedVersion\":7,\"commandId\":\"command-1234567890\"}"))
                .andExpect(status().isNotFound());
        verifyNoInteractions(recovery, coordinator);
        when(diagnostics.list("t", "ACTIVE", "cursor", 10)).thenReturn(new CursorPage<>(List.of(), null));
        mvc.perform(get("/api/tasks/t/session-diagnostics").param("filter", "ACTIVE").param("cursor", "cursor").param("limit", "10"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items").isArray());
        verify(diagnostics).list("t", "ACTIVE", "cursor", 10);
    }
}
