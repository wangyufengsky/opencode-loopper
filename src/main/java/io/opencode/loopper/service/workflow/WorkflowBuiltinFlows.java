package io.opencode.loopper.service.workflow;

import io.opencode.loopper.service.roles.RolePublishingService;
import io.opencode.loopper.workflow.*;
import java.io.IOException;
import java.util.*;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/** Bundled flows compose the same presets the user can place on the canvas. */
@Service
public class WorkflowBuiltinFlows {
    private final List<Flow> flows;
    private final WorkflowNodePresets presets;
    private final WorkflowTemplates templates;
    private final RolePublishingService roles;
    public record Node(String id,String presetId,int presetVersion,List<WorkflowGraph.Input> inputs,String title,String task) { }
    public record Flow(String id,String title,String description,List<Node> nodes,List<WorkflowGraph.Edge> edges,List<WorkflowGraph.PublicInput> inputs,CanvasLayout layout) { }
    public record Document(int schemaVersion,List<Flow> flows) { }
    public WorkflowBuiltinFlows(ObjectMapper json,WorkflowNodePresets presets,WorkflowTemplates templates,RolePublishingService roles) {
        this.presets=presets;this.templates=templates;this.roles=roles;
        try(var input=new ClassPathResource("workflows/templates.json").getInputStream()) {
            byte[] bytes=input.readNBytes(256*1024+1);if(bytes.length>256*1024)throw new IllegalStateException("Builtin workflow resource exceeds limit");
            var document=json.readValue(bytes,Document.class);
            if(document.schemaVersion()!=1 || document.flows()==null || document.flows().size()>100)throw new IllegalStateException("Invalid builtin workflow document");
            flows=List.copyOf(document.flows());var ids=new HashSet<String>();
            for(var flow:flows)if(flow.id()==null || !flow.id().matches("builtin\\.[a-z][a-z0-9.-]{0,79}") || !ids.add(flow.id()))throw new IllegalStateException("Invalid builtin workflow identity");
        }catch(IOException failure){throw new IllegalStateException("Cannot read builtin workflows",failure);}
    }
    @EventListener(ApplicationReadyEvent.class)
    public void publish() {
        roles.seedBuiltin(); // Ordered explicitly; resolving presets is a read and never bootstraps roles.
        for(var flow:flows) {
            var nodes=flow.nodes().stream().map(this::node).toList();
            var graph=new WorkflowGraph(1,nodes,flow.edges(),flow.inputs());
            if(!WorkflowGraphValidator.validate(graph,WorkflowGraphValidator.Mode.EXECUTION).isEmpty())throw new IllegalStateException("Invalid builtin workflow graph: "+flow.id());
            templates.installBuiltin(flow.id(),flow.title(),flow.description(),graph,flow.layout());
        }
    }
    private WorkflowGraph.Node node(Node value) {
        var base=presets.get(value.presetId(),value.presetVersion()).node();
        return new WorkflowGraph.Node(value.id(),value.title()==null?base.title():value.title(),base.kind(),base.moduleId(),base.moduleVersion(),base.roleId(),value.task()==null?base.task():value.task(),value.inputs(),base.outputs(),
                base.outcomes(),base.completion(),base.maxRetries(),base.pauseAfter(),base.parameters(),base.roleRevisionId());
    }
}
