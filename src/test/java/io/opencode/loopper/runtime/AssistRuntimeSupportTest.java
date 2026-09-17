package io.opencode.loopper.runtime;

import io.opencode.loopper.persistence.AssistMapper;
import io.opencode.loopper.service.assist.*;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AssistRuntimeSupportTest {
    @Test void implementationExplicitlyDeniesDisabledToolsAndFreezesExactPolicy() {
        var mapper=mock(AssistMapper.class);var policy=mock(AssistToolPolicyService.class);var inventory=mock(OpenCodeToolInventory.class);
        var scopes=mock(AssistScopeService.class);Path path=Path.of("/project");when(mapper.projectsAt(path.toString())).thenReturn(List.of("p"));
        when(inventory.tools(path,"external")).thenReturn(new McpToolCatalogReader.Catalog(List.of(new McpToolCatalogReader.Tool("allowed","read"),new McpToolCatalogReader.Tool("off","write")),true,null));
        when(policy.catalog(eq("p"),eq("external"),anyList(),eq(true))).thenReturn(List.of(setting("allowed",true),setting("off",false)));
        when(policy.catalog(eq("p"),eq(AssistToolCatalog.SERVER),anyList(),eq(true))).thenReturn(List.of(setting("read_document",true),setting("generate_word",false)));
        var support=new AssistRuntimeSupport(mapper,policy,inventory,scopes,new ObjectMapper(),mock(io.opencode.loopper.service.DocumentDevelopmentScope.class));
        var permissions=support.permissions(path,OpenCodeClient.SessionProfile.IMPLEMENTATION,List.of("external","private","private_assist"),"private",false);
        assertThat(permissions).contains(Map.of("permission","external_*","pattern","*","action","deny"),Map.of("permission","external_allowed","pattern","*","action","allow"));
        assertThat(permissions).doesNotContain(Map.of("permission","external_off","pattern","*","action","allow"));
        support.remember("s","g",path,OpenCodeClient.SessionProfile.IMPLEMENTATION,permissions,"private");
        var saved=org.mockito.ArgumentCaptor.forClass(AssistMapper.Session.class);verify(mapper).insertSession(saved.capture());assertThat(saved.getValue().toolsJson()).isEqualTo("[\"read_document\"]");
        when(mapper.session("s")).thenReturn(saved.getValue());support.remember("s","g",path,OpenCodeClient.SessionProfile.IMPLEMENTATION,permissions,"private");
        assertThatThrownBy(()->support.remember("s","g",path,OpenCodeClient.SessionProfile.IMPLEMENTATION,List.of(),"private")).isInstanceOf(AssistFailure.class);
    }
    @Test void candidatesKeepOnlyExplicitAuxiliaryExtrasAndNeverQueryThirdPartyCatalogs() {
        var mapper=mock(AssistMapper.class);var policy=mock(AssistToolPolicyService.class);var inventory=mock(OpenCodeToolInventory.class);
        var support=new AssistRuntimeSupport(mapper,policy,inventory,mock(AssistScopeService.class),new ObjectMapper(),mock(io.opencode.loopper.service.DocumentDevelopmentScope.class));
        var permission=support.permissions(Path.of("/project"),OpenCodeClient.SessionProfile.DECOMPOSER_CANDIDATE_READ_ONLY,List.of("third"),"private",true);
        assertThat(permission).noneMatch(p->p.get("permission").equals("third_*")&&p.get("action").equals("allow"));verifyNoInteractions(inventory);
    }
    @Test void knowledgeGetsOnlyExplicitPrivateReadToolsAndNoThirdPartyOrTaskCapabilities() {
        var mapper=mock(AssistMapper.class);var policy=mock(AssistToolPolicyService.class);var inventory=mock(OpenCodeToolInventory.class);
        when(policy.catalog(anyString(), eq(AssistToolCatalog.SERVER), anyList(), eq(true)))
            .thenReturn(AssistToolCatalog.tools().stream().map(t -> setting(t.name(), true)).toList());
        var support=new AssistRuntimeSupport(mapper,policy,inventory,mock(AssistScopeService.class),new ObjectMapper(),mock(io.opencode.loopper.service.DocumentDevelopmentScope.class));
        for (var profile : List.of(OpenCodeClient.SessionProfile.KNOWLEDGE_READ_ONLY, OpenCodeClient.SessionProfile.KNOWLEDGE_INTERACTIVE_READ_ONLY)) {
        var rules=support.permissions(Path.of("/project"),profile,List.of("external","private"),"private",false);
        var expected = new ArrayList<>(List.of(
            "private_assist_list_knowledge_sources", "private_assist_browse_knowledge_source", "private_assist_search_knowledge", "private_assist_read_knowledge_source",
            "private_assist_list_database_connections", "private_assist_inspect_database_schema", "private_assist_query_database_readonly",
            "private_assist_inspect_knowledge_git", "private_assist_list_knowledge_git_authors", "private_assist_search_knowledge_git_commits",
            "private_assist_read_knowledge_git_commit", "private_assist_read_knowledge_git_file", "private_assist_blame_knowledge_git_lines"));
        if (profile == OpenCodeClient.SessionProfile.KNOWLEDGE_INTERACTIVE_READ_ONLY) expected.add("question");
        assertThat(rules.stream().filter(r -> r.get("action").equals("allow")).map(r -> r.get("permission"))).containsExactlyInAnyOrderElementsOf(expected);
        assertThat(OpenCodeAgentPolicy.stepLimit(profile)).isZero();
        }
        assertThat(AssistToolCatalog.allowed("IMPLEMENTATION")).noneMatch(AssistToolCatalog::knowledgeTool);
        verifyNoInteractions(inventory);
    }
    private static AssistToolPolicyService.View setting(String name,boolean enabled){return new AssistToolPolicyService.View(name,true,false,true,"INHERIT",enabled,"GLOBAL",0,-1,"");}
}
