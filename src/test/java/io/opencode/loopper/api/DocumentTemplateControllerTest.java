package io.opencode.loopper.api;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import io.opencode.loopper.persistence.*;
import io.opencode.loopper.service.*;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class DocumentTemplateControllerTest {
    private final DocumentTemplateService service = mock(DocumentTemplateService.class);
    private final DocumentTemplateReadService reads = mock(DocumentTemplateReadService.class);
    private final DocumentTemplateControl controls = mock(DocumentTemplateControl.class);
    private final DocumentTemplateCoordinator coordinator = mock(DocumentTemplateCoordinator.class);
    private final DocumentRequirementReportService reports = mock(DocumentRequirementReportService.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new DocumentTemplateController(service, reads, controls, coordinator, reports))
            .setControllerAdvice(new ApiExceptionHandler()).build();
    private MockMultipartFile metadata() {
        return new MockMultipartFile("metadata", "", "application/json", """
            {"requestKey":"stable-request-123456","templateId":"REQUIREMENT_CODE_REVIEW","templateVersion":"1",
             "projectId":"project","branchId":"local:refs/heads/main"}
            """.getBytes(StandardCharsets.UTF_8));
    }
    private MockMultipartFile file() { return new MockMultipartFile("files", "需求.md", "text/markdown", "必须鉴权".getBytes(StandardCharsets.UTF_8)); }
    @Test void multipartAndCommandsRequireLocalUiBeforeBusinessSideEffects() throws Exception {
        mvc.perform(multipart("/api/template-tasks/document-runs").file(metadata()).file(file()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errorCode").value("LOCAL_UI_HEADER_REQUIRED"));
        mvc.perform(post("/api/template-tasks/document-runs/run/cancel").contentType(MediaType.APPLICATION_JSON)
                .content("{\"requestKey\":\"stable-cancel-123456\",\"expectedVersion\":1}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service, controls, coordinator, reports);
    }
    @Test void acceptsMultipleFilesWithoutDatesAndDispatchesOnlyTheDocumentOwner() throws Exception {
        when(service.create(any(), any())).thenReturn(new DocumentTemplateRunRow("run", "key", "sha", "project",
                "REQUIREMENT_CODE_REVIEW", "1", "需求评审", "ANALYZING", null, "{}", null, "{}", null, null, 0,
                null, null, 0, "created", "updated", 1));
        when(reads.overview("run")).thenReturn(new DocumentTemplateReadService.Overview("run", "project", "REQUIREMENT_CODE_REVIEW",
                "1", "需求评审", "ANALYZING", null, null, null, null, 0, 1, "created", "updated", true, false, false,
                List.of(), new DocumentProgressMapper.Progress(0, 0, 0, 0, 0, 0, 1), true, "frozen-sha", null));
        mvc.perform(multipart("/api/template-tasks/document-runs").file(metadata()).file(file())
                .file(new MockMultipartFile("files", "权限.md", "text/markdown", "未授权拒绝".getBytes(StandardCharsets.UTF_8)))
                .header("X-Loopper-Local-UI", "1"))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.id").value("run"))
                .andExpect(jsonPath("$.taskId").doesNotExist()).andExpect(jsonPath("$.contractJson").doesNotExist());
        verify(service).create(argThat(request -> request.branchId().equals("local:refs/heads/main")), argThat(files -> {
            assertThat(files).hasSize(2); assertThat(files.getFirst().filename()).isEqualTo("需求.md"); return true;
        }));
        verify(coordinator).dispatch("run"); verifyNoInteractions(controls, reports);
    }
    @Test void rejectsMissingFilesBeforeAdmissionAndResolvesDownloadLiteralBeforeArtifactRoute() throws Exception {
        mvc.perform(multipart("/api/template-tasks/document-runs").file(metadata()).header("X-Loopper-Local-UI", "1"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errorCode").value("UPLOAD_PART_REQUIRED"));
        verifyNoInteractions(service, coordinator);
        when(reports.named("run", "summary.md")).thenThrow(new NotFoundException("报告尚未生成"));
        mvc.perform(get("/api/template-tasks/document-runs/run/reports/content").param("name", "summary.md"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.detail").value("报告尚未生成"));
        verify(reports).named("run", "summary.md"); verify(reports, never()).read(any(), any());
    }
}
