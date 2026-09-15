package io.opencode.loopper.api;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import io.opencode.loopper.service.*;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class DocumentBatchRetryControllerTest {
    @Test void rejectsMissingLocalAuthorityAndStaleBatchWithoutDispatch() throws Exception {
        var retries = mock(DocumentBatchRetryService.class);
        var coordinator = mock(DocumentTemplateCoordinator.class);
        var mvc = MockMvcBuilders.standaloneSetup(new DocumentBatchRetryController(retries, coordinator))
                .setControllerAdvice(new ApiExceptionHandler()).build();
        String path = "/api/template-tasks/document-runs/run/batches/batch/retry";
        mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content("{\"expectedVersion\":4}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(retries, coordinator);
        when(retries.retry("run", "batch", 4)).thenThrow(new ConflictException("DOCUMENT_BATCH_SCOPE_STALE", "原文已变化"));
        mvc.perform(post(path).header("X-Loopper-Local-UI", "1").contentType(MediaType.APPLICATION_JSON).content("{\"expectedVersion\":4}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.errorCode").value("DOCUMENT_BATCH_SCOPE_STALE"));
        verifyNoInteractions(coordinator);
    }
}
