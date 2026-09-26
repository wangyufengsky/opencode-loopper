package io.opencode.loopper.service.knowledge;

import io.opencode.loopper.persistence.WorkflowKnowledgeMapper;
import io.opencode.loopper.service.assist.*;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class WorkflowKnowledgeToolsTest {
    @Test void judgesIndependentlySearchSourcesWithoutLiveDatabaseConnections() {
        var access=mock(WorkflowKnowledgeAccess.class); var bindings=mock(WorkflowKnowledgeBindings.class);
        var mapper=mock(WorkflowKnowledgeMapper.class); var reads=mock(KnowledgeReadOperations.class);
        var git=mock(KnowledgeGit.class);
        var tools=new WorkflowKnowledgeTools(access,bindings,mapper,reads,git,new ObjectMapper());
        var source=new KnowledgeSources.Bound("doc","DOCUMENTS","规范","/project",null,"READY","",3);
        var binding=new WorkflowKnowledgeMapper.Binding("TASK","task","project","[]","frozen");
        when(access.require(any())).thenReturn(binding); when(bindings.sources(binding)).thenReturn(List.of(source));
        when(mapper.git(anyString())).thenReturn("null");
        when(reads.read(anyString(),any(),anyString(),anyMap())).thenReturn(Map.of("matches",List.of("独立查证")));
        for (String profile : List.of("JUDGE_READ_ONLY","JUDGE_CANDIDATE_READ_ONLY","REVIEWER_CANDIDATE_READ_ONLY","SOURCE_DETAILED_DESIGN_NO_TOOLS")) {
            var scope=new AssistScopeService.Scope("session","owner","project",null,null,null,null,profile,Path.of("/project"),
                    AssistToolCatalog.allowed(profile),List.of(new DatabaseConnectionService.Bound("db","live",null,"ref",1)));
            assertThat(scope.tools()).contains("search_project_knowledge","read_knowledge_source").doesNotContain("query_database_readonly");
            assertThat(tools.call(scope,"search_project_knowledge",Map.of("query","独立查证"),UUID.randomUUID().toString())).containsKey("matches");
        }
        var selection=ArgumentCaptor.forClass(KnowledgeSources.Selection.class);
        verify(reads,times(4)).read(eq("workflow:session"),selection.capture(),eq("search_project_knowledge"),anyMap());
        assertThat(selection.getAllValues()).allSatisfy(s->{assertThat(s.sources()).containsExactly(source);assertThat(s.connections()).isEmpty();});
        for (String profile : List.of("ROUTER_NO_TOOLS","COMPILER_REPAIR_NO_TOOLS","MACHINE_FINALIZER_NO_TOOLS","JUDGE_FINALIZER_NO_TOOLS"))
            assertThat(AssistToolCatalog.allowed(profile)).noneMatch(AssistToolCatalog::knowledgeTool);
    }
}
