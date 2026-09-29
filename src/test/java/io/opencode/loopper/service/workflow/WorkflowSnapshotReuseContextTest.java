package io.opencode.loopper.service.workflow;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import io.opencode.loopper.persistence.*;
import io.opencode.loopper.service.roles.*;
import io.opencode.loopper.template.SnapshotReview;
import io.opencode.loopper.workflow.*;
import java.util.*;
import org.junit.jupiter.api.*;
import tools.jackson.databind.ObjectMapper;

class WorkflowSnapshotReuseContextTest {
    final RoleConfigurationService roles=mock(RoleConfigurationService.class);
    final WorkflowKnowledgeMapper knowledge=mock(WorkflowKnowledgeMapper.class);
    final WorkflowPlanMapper plans=mock(WorkflowPlanMapper.class);
    final WorkflowSnapshotReuseMapper reuse=mock(WorkflowSnapshotReuseMapper.class);
    final ObjectMapper json=new ObjectMapper();final WorkflowEncoding encoding=new WorkflowEncoding(json);
    final WorkflowSnapshotReuseContext context=new WorkflowSnapshotReuseContext(roles,knowledge,plans,reuse,encoding);
    RoleConfigurationService.ResolvedRole role;String project="project",scope="scope",directory="/project",model="model",objective="review",task="inspect",extra="notes";int ordinal=1;
    @BeforeEach void setup(){
        role=role(List.of(),"fixed policy");when(roles.resolveFrozen(any(),eq("WORKFLOW_READ_ONLY"))).thenAnswer(i->Optional.ofNullable(role));
        when(plans.find("requirement")).thenAnswer(i->Optional.of(new WorkflowRows.Requirement("requirement",project,"title",objective,"RUNNING",1,1,"template",1,"{}",1,"t","t")));
        when(knowledge.binding("WORKFLOW_ATTEMPT","attempt")).thenReturn(new WorkflowKnowledgeMapper.Binding("WORKFLOW_ATTEMPT","attempt","project","[\"mutable-project-root\"]","t"));
    }
    @Test void projectScopeModelTaskAndExtraInputChangesEachInvalidateReuse(){
        String original=fingerprint().orElseThrow();
        project="other";assertThat(fingerprint().orElseThrow()).isNotEqualTo(original);project="project";
        scope="subdirectory";assertThat(fingerprint().orElseThrow()).isNotEqualTo(original);scope="scope";
        directory="/elsewhere";assertThat(fingerprint().orElseThrow()).isNotEqualTo(original);directory="/project";
        model="other model";assertThat(fingerprint().orElseThrow()).isNotEqualTo(original);model="model";
        objective="other requirement";assertThat(fingerprint().orElseThrow()).isNotEqualTo(original);objective="review";
        task="other task";assertThat(fingerprint().orElseThrow()).isNotEqualTo(original);task="inspect";
        extra="other instruction";assertThat(fingerprint().orElseThrow()).isNotEqualTo(original);extra="notes";
        role=role(List.of(),"new fixed policy");assertThat(fingerprint().orElseThrow()).isNotEqualTo(original);
    }
    @Test void deniedKnowledgeDoesNotBecomeAuthorizationFromBindingMetadata(){
        assertThat(fingerprint()).isPresent();role=role(List.of(RoleCapabilities.Capability.PROJECT_KNOWLEDGE),"fixed policy");assertThat(fingerprint()).isEmpty();
        when(knowledge.binding("WORKFLOW_ATTEMPT","attempt")).thenReturn(null);assertThat(fingerprint()).isEmpty();
        when(knowledge.binding("WORKFLOW_ATTEMPT","attempt")).thenReturn(new WorkflowKnowledgeMapper.Binding("WORKFLOW_ATTEMPT","attempt","project","[]","t"));assertThat(fingerprint()).isPresent();
        role=role(List.of(RoleCapabilities.Capability.NATIVE_TOOLS),"fixed policy");assertThat(fingerprint()).isEmpty();
    }
    @Test void retriesAndAnyPriorFailureDisableReuse(){
        ordinal=2;assertThat(fingerprint()).isEmpty();ordinal=1;when(reuse.priorFailure("requirement")).thenReturn(true);assertThat(fingerprint()).isEmpty();
    }
    private Optional<String> fingerprint(){
        var node=new WorkflowGraph.Node("analysis","analysis",WorkflowGraph.NodeKind.WORK,"snapshot.analyze",1,"role",task,List.of(),List.of(),List.of(),new WorkflowGraph.Completion(WorkflowGraph.CompletionKind.DELIVERABLES,"deliver",null),0,false,Map.of());
        var launch=new WorkflowModelMapper.Launch("attempt","requirement","PREPARING",directory,model,null,null,null,false,null,0,"t","t");
        var attempt=new WorkflowExecutionRows.Attempt("attempt","node",ordinal,1,"PREPARING","{}","hash",encoding.encode(role),"model.readonly.v1",null,0,"t","t");
        var inputs=new WorkflowDelivery.Inputs(1,"requirement",1,"analysis",objective,List.of(new WorkflowDelivery.Input("instruction",WorkflowGraph.DataKind.TEXT,"REQUIREMENT","notes",null,null,"hash",json.valueToTree(extra))));
        var unit=new SnapshotReview.Unit("unit","a.txt",null,"ADD","code",null,List.of(new SnapshotReview.Reference("target","a.txt","blob",1,1,"code")));
        var batch=new SnapshotReview.Input("SNAPSHOT_ANALYSIS",List.of(unit),List.of(),List.of(),List.of(),"batch",null,SnapshotReview.COMPACT);
        var prepared=new WorkflowSnapshotWork.Input(1,"snapshot.analyze",null,"source",0,1,"target",null,batch,null,List.of());
        var snapshot=new SnapshotReview.Snapshot("source",null,"target",null,"full-tree","t",null,null,"full",false,false,List.of(),List.of(unit),scope);
        return context.fingerprint(launch,attempt,node,inputs,prepared,snapshot);
    }
    private RoleConfigurationService.ResolvedRole role(List<RoleCapabilities.Capability> capabilities,String instructions){return new RoleConfigurationService.ResolvedRole("role","revision","hash","WORKFLOW_READ_ONLY","WORKFLOW_READ_ONLY",Map.of(),"INTERSECT",List.of(),List.of(),List.of(),"INHERIT_WORKFLOW","WORKFLOW_ADAPTER",capabilities,instructions);}
}
