package io.opencode.loopper.workflow;

import java.util.List;

/** Editable starting content for a node, distinct from its execution adapter and a saved flow. */
public record WorkflowNodePreset(String id,int version,String title,String description,List<InputPort> inputs,WorkflowGraph.Node node) {
    public WorkflowNodePreset { inputs=inputs==null?List.of():List.copyOf(inputs); }
    public record InputPort(String name,String title,WorkflowGraph.DataKind kind,boolean required) { }
    public record Document(int schemaVersion,List<WorkflowNodePreset> presets) {
        public Document { presets=presets==null?List.of():List.copyOf(presets); }
    }
}
