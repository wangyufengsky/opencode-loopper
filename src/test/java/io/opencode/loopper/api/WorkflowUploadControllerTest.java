package io.opencode.loopper.api;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import io.opencode.loopper.service.DocumentTemplateStorage;
import io.opencode.loopper.service.workflow.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class WorkflowUploadControllerTest {
    private final WorkflowUploads uploads=mock(WorkflowUploads.class);
    private final WorkflowCodeFiles files=mock(WorkflowCodeFiles.class);
    private final org.springframework.test.web.servlet.MockMvc mvc=MockMvcBuilders.standaloneSetup(new WorkflowUploadController(uploads,files)).setControllerAdvice(new ApiExceptionHandler()).build();
    @Test void uploadRequiresUiBeforeReadingFileBytesAndKeepsMetadata() throws Exception {
        var metadata=new MockMultipartFile("metadata","metadata.json","application/json","{\"requestKey\":\"request-key-for-documents\",\"expectedVersion\":3,\"expectedRevision\":2}".getBytes());
        var file=new MockMultipartFile("files","需求.md","text/markdown","原文".getBytes());
        mvc.perform(multipart("/api/workflows/requirements/r/documents").file(metadata).file(file)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.errorCode").value("LOCAL_UI_HEADER_REQUIRED"));verifyNoInteractions(uploads,files);
        mvc.perform(multipart("/api/workflows/requirements/r/documents").file(metadata).file(file).header("X-Loopper-Local-UI","1")).andExpect(status().isOk());
        verify(uploads).upload(eq("r"),eq(new WorkflowUploadStore.Request("request-key-for-documents",3,2)),argThat(values->{
            assertThat(values).hasSize(1);assertThat(values.getFirst().filename()).isEqualTo("需求.md");
            assertThat(values.getFirst().bytes()).isEqualTo("原文".getBytes());return true;
        }));
    }
    @Test void historyUsesScopedCursorAndExplicitLimit() throws Exception {
        when(uploads.list("r","cursor",7)).thenReturn(new CursorPage<>(List.of(),null));
        mvc.perform(get("/api/workflows/requirements/r/documents").param("cursor","cursor").param("limit","7")).andExpect(status().isOk());
        verify(uploads).list("r","cursor",7);verifyNoInteractions(files);
    }
}
