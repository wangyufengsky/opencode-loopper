package io.opencode.loopper.api;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import io.opencode.loopper.persistence.TaskRow;
import io.opencode.loopper.persistence.TemplateTaskReadMapper;
import io.opencode.loopper.service.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class TemplateTaskControllerTest {
    private final TemplateTaskService admission = mock(TemplateTaskService.class);
    private final ProjectBranchService branches = mock(ProjectBranchService.class);
    private final TemplateTaskReadService reads = mock(TemplateTaskReadService.class);
    private final TaskService tasks = mock(TaskService.class);
    private final TemplateBatchRetryService retries = mock(TemplateBatchRetryService.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new TemplateTaskController(admission, branches, reads, tasks, retries))
            .setControllerAdvice(new ApiExceptionHandler()).build();

    @Test void batchRetryRequiresLocalAuthorityBeforeStartingAnyWork() throws Exception {
        mvc.perform(post("/api/template-tasks/task/batches/batch/retry").contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedVersion\":4}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errorCode").value("LOCAL_UI_HEADER_REQUIRED"));
        verifyNoInteractions(retries);
    }

    @Test void localHeaderIsRequiredBeforeConfirmationOrStart() throws Exception {
        mvc.perform(post("/api/template-tasks").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errorCode").value("LOCAL_UI_HEADER_REQUIRED"));
        mvc.perform(post("/api/template-tasks/task/start")).andExpect(status().isBadRequest());
        verifyNoInteractions(admission, tasks);
    }

    @Test void confirmationIsSeparateAndDoesNotStartTask() throws Exception {
        when(admission.create(any(), eq(false))).thenReturn(task("PENDING_START"));
        mvc.perform(post("/api/template-tasks").header("X-Loopper-Local-UI", "1").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestKey\":\"a-stable-request-key\",\"templateId\":\"CODE_REVIEW\",\"templateVersion\":\"1\",\"projectId\":\"project\",\"branchId\":\"local:refs/heads/main\",\"startDate\":\"2026-09-11\",\"endDate\":\"2026-09-11\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.state").value("PENDING_START"));
        verify(admission).create(argThat(request -> request.story() == null), eq(false));
        verifyNoInteractions(tasks);
    }

    @Test void historyIsPagedAndDoesNotReturnReportOrSourceBodies() throws Exception {
        when(reads.runs(null, "cursor", 50)).thenReturn(new CursorPage<>(List.of(new TemplateTaskReadMapper.RunSummary(
                "task", "代码审查", "COMPLETED", "项目", "CODE_REVIEW", "main", "2026-09-11", "2026-09-11", 0, "created", "updated")), null));
        mvc.perform(get("/api/template-tasks").param("cursor", "cursor"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].title").value("代码审查"))
                .andExpect(jsonPath("$.items[0].content").doesNotExist()).andExpect(jsonPath("$.items[0].snapshotJson").doesNotExist());
    }
    private TaskRow task(String state) {
        return new TaskRow("task", "project", "draft", "代码审查", state, null, null, null, null, "created", "updated", 0,
                null, null, null, "TEMPLATE_REPORT", "ISOLATED_REPORT");
    }
}
