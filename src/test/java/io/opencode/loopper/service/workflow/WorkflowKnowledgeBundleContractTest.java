package io.opencode.loopper.service.workflow;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static io.opencode.loopper.workflow.WorkflowGraph.*;
import io.opencode.loopper.persistence.WorkflowExecutionRows.Attempt;
import io.opencode.loopper.persistence.WorkflowKnowledgeMapper;
import io.opencode.loopper.service.BadRequestException;
import io.opencode.loopper.workflow.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class WorkflowKnowledgeBundleContractTest {
    private final WorkflowKnowledgeMapper mapper=mock(WorkflowKnowledgeMapper.class);
    private final ObjectMapper json=new ObjectMapper();
    private final WorkflowKnowledgeBundleContract contract=new WorkflowKnowledgeBundleContract(mapper,json);
    private final Attempt attempt=new Attempt("a","node",1,1,"RUNNING","{}","hash","{}","model.readonly.v1","session",2,"now","now");
    private final Node node=new Node("node","检索",NodeKind.WORK,WorkflowKnowledgeBundle.MODULE,1,"role","查询并交付资料",List.of(),
            List.of(new Output("result","结论",DataKind.TEXT,true),new Output("evidence","证据",DataKind.JSON,true)),List.of(),
            new Completion(CompletionKind.DELIVERABLES,"交付实际证据",null),0,false,Map.of());
    @Test void versionOverflowAndModelSuppliedBodiesCannotReplaceServerSavedContent() {
        for(var value:List.of(Map.of("version",4294967297L,"references",List.of(),"limitations",List.of("无资料")),
                Map.of("version",1,"entries",List.of(Map.of("content","伪造正文")),"limitations",List.of())))
            assertThatThrownBy(()->contract.accept(attempt,node,delivery(value))).isInstanceOf(BadRequestException.class);
        verifyNoInteractions(mapper);
    }
    @Test void oversizedMalformedOrMissingOriginalReceiptsFailWithoutPartialOutput() {
        for(String body:List.of("{\"text\":\""+"x".repeat(131072)+"\"}","[]","null","{broken")) {
            when(mapper.nodeEvidence("session","WORKFLOW_ATTEMPT:a","original")).thenReturn(new WorkflowKnowledgeMapper.NodeEvidence("original","read_knowledge_source","now",body));
            assertThatThrownBy(()->contract.accept(attempt,node,delivery(Map.of("version",1,"references",List.of("call:original"),"limitations",List.of())))).isInstanceOf(BadRequestException.class);
        }
        when(mapper.nodeEvidence("session","WORKFLOW_ATTEMPT:a","original")).thenReturn(null);
        assertThatThrownBy(()->contract.accept(attempt,node,delivery(Map.of("version",1,"references",List.of("call:original"),"limitations",List.of())))).isInstanceOf(BadRequestException.class);
    }
    @Test void aggregateLimitRejectsTheWholeSelectionAndDiscoveryRemainsDiscovery() {
        String large=json.writeValueAsString(Map.of("kind","CODE","text","x".repeat(70000)));
        when(mapper.nodeEvidence(eq("session"),eq("WORKFLOW_ATTEMPT:a"),anyString())).thenAnswer(call->new WorkflowKnowledgeMapper.NodeEvidence(call.getArgument(2),"read_knowledge_source","now",large));
        assertThatThrownBy(()->contract.accept(attempt,node,delivery(Map.of("version",1,"references",List.of("call:first","call:second"),"limitations",List.of())))).isInstanceOf(BadRequestException.class);
        when(mapper.nodeEvidence("session","WORKFLOW_ATTEMPT:a","search")).thenReturn(new WorkflowKnowledgeMapper.NodeEvidence("search","search_project_knowledge","now",json.writeValueAsString(Map.of("items",List.of(),"incomplete",true,"nextCursor","next"))));
        var accepted=contract.accept(attempt,node,delivery(Map.of("version",1,"references",List.of("call:search"),"limitations",List.of("仅完成一次检索，未覆盖全部资料"))));
        var entry=accepted.outputs().get("evidence").content().path("entries").get(0);
        assertThat(entry.path("toolName").asString()).isEqualTo("search_project_knowledge");
        assertThat(entry.path("content").path("incomplete").asBoolean()).isTrue();assertThat(entry.path("content").path("nextCursor").asString()).isEqualTo("next");
        assertThat(entry.path("sha256").asString()).isEqualTo(WorkflowEncoding.hash(json.writeValueAsString(entry.path("content"))));
    }
    private WorkflowDelivery delivery(Object candidate){return new WorkflowDelivery("资料说明",null,Map.of("result",new WorkflowDelivery.Value(DataKind.TEXT,json.valueToTree("结论")),"evidence",new WorkflowDelivery.Value(DataKind.JSON,json.valueToTree(candidate))));}
}
