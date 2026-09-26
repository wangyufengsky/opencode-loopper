package io.opencode.loopper.service.knowledge;

import io.opencode.loopper.persistence.KnowledgeMapper;
import io.opencode.loopper.persistence.KnowledgeRows;
import io.opencode.loopper.service.KnowledgeEventHub;
import io.opencode.loopper.service.assist.AssistScopeService;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class KnowledgeToolsReadIdentityTest {
    @Test void sharedExecutorPreservesHistoricalGitCursorOwnerAndTurnScopedSearchIdentity() {
        var mapper=mock(KnowledgeMapper.class);var sources=mock(KnowledgeSources.class);
        var reads=mock(KnowledgeReadOperations.class);var scopes=mock(AssistScopeService.class);
        var tools=new KnowledgeTools(mapper,sources,reads,new ObjectMapper(),mock(KnowledgeEventHub.class),scopes);
        var conversation=mock(KnowledgeRows.Conversation.class);var turn=mock(KnowledgeRows.Turn.class);
        when(conversation.id()).thenReturn("conversation");when(conversation.projectId()).thenReturn("project");when(conversation.state()).thenReturn("RUNNING");
        when(turn.id()).thenReturn("turn");when(turn.state()).thenReturn("RUNNING");
        when(mapper.remote("session")).thenReturn(Optional.of(conversation));when(mapper.active("conversation")).thenReturn(Optional.of(turn));
        when(mapper.citationCount("turn")).thenReturn(100);
        when(reads.read(anyString(),any(),anyString(),anyMap())).thenReturn(Map.of("matches",List.of()));
        var scope=new AssistScopeService.Scope("session","owner","project",null,null,null,null,"KNOWLEDGE_RESEARCH_READ_ONLY",Path.of("/project"),List.of(),List.of());
        tools.call(scope,"search_knowledge_git_commits",Map.of("scope","grant","cursor","existing-git-cursor"));
        tools.call(scope,"search_project_knowledge",Map.of("scope","grant","query","business"));
        verify(reads).read(eq("conversation"),any(),eq("search_knowledge_git_commits"),anyMap());
        verify(reads).read(eq("mcp:conversation:turn"),any(),eq("search_project_knowledge"),anyMap());
    }
}
