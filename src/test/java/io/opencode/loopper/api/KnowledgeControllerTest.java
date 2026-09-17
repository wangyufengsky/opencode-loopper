package io.opencode.loopper.api;
import io.opencode.loopper.service.knowledge.*;
import io.opencode.loopper.service.KnowledgeEventHub;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
class KnowledgeControllerTest {
    @Test void writeAuthorizationRunsBeforeAnyModelOrSourceOperation() throws Exception {
        var conversations=mock(KnowledgeConversations.class);var coordinator=mock(KnowledgeCoordinator.class);
        var sources=mock(KnowledgeSources.class);var reader=mock(KnowledgeReader.class);var databases=mock(io.opencode.loopper.service.assist.DatabaseQueryService.class);
        var questions=mock(KnowledgeQuestions.class);var git=mock(KnowledgeGit.class);
        var mvc=MockMvcBuilders.standaloneSetup(new KnowledgeController(conversations,coordinator,new KnowledgeEventHub(),questions),new KnowledgeSourceController(sources,reader,databases,git,mock(KnowledgeSearchService.class))).setControllerAdvice(new ApiExceptionHandler()).build();
        mvc.perform(post("/api/knowledge/conversations").contentType("application/json").content("{}"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errorCode").value("LOCAL_UI_HEADER_REQUIRED"));
        mvc.perform(post("/api/knowledge/conversations/id/messages").contentType("application/json").content("{\"text\":\"问题\",\"idempotencyKey\":\"1234567890123456\"}"))
            .andExpect(status().isBadRequest());
        mvc.perform(post("/api/knowledge/conversations/id/stop")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/projects/p/knowledge-sources/directories").contentType("application/json").content("{\"path\":\"/tmp\"}"))
            .andExpect(status().isBadRequest());
        mvc.perform(post("/api/knowledge/conversations/id/archive").contentType("application/json").content("{\"archived\":true,\"version\":0}"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errorCode").value("LOCAL_UI_HEADER_REQUIRED"));
        mvc.perform(post("/api/knowledge/conversations/id/questions/q/reply").contentType("application/json").content("{\"answers\":[[\"回答\"]],\"idempotencyKey\":\"1234567890123456\",\"version\":0}"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errorCode").value("LOCAL_UI_HEADER_REQUIRED"));
        verifyNoInteractions(conversations,coordinator,sources,reader,databases,questions,git);
    }
}
