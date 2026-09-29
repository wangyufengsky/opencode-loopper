package io.opencode.loopper.service.workflow;
import io.opencode.loopper.workflow.WorkflowGraph;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
@Service
public class WorkflowDispatchExecution {
    private final WorkflowControls controls;
    private final WorkflowModelAdmission models;
    private final WorkflowNodeActions nodes;
    private final WorkflowSystemDispatch systems;
    public WorkflowDispatchExecution(WorkflowControls controls,WorkflowModelAdmission models,WorkflowNodeActions nodes,WorkflowSystemDispatch systems) { this.controls=controls;this.models=models;this.nodes=nodes;this.systems=systems; }
    @Transactional(propagation=Propagation.NOT_SUPPORTED)
    public void advance(String id) {
        for(int count=0;count<4;count++) {
            var prepared=controls.prepare(id);if(prepared==null)return;
            try {
                String key=UUID.randomUUID().toString();
                if(prepared.kind()==WorkflowGraph.NodeKind.HUMAN)
                    nodes.dispatchHuman(id,prepared.nodeKey(),new WorkflowNodeActions.Start(key,prepared.version(),null),prepared.permit());
                else if(prepared.kind()==WorkflowGraph.NodeKind.SYSTEM)
                    systems.dispatch(prepared.moduleId(),id,prepared.nodeKey(),new WorkflowNodeActions.Start(key,prepared.version(),null),prepared.permit());
                else models.dispatch(id,prepared.nodeKey(),new WorkflowModelAdmission.Start(key,prepared.version(),null,prepared.model()),prepared.permit());
            } catch(RuntimeException failure) {
                String code=WorkflowFailures.code(failure);
                if(!Set.of("WORKFLOW_VERSION_CONFLICT","WORKFLOW_NODE_NOT_READY").contains(code))controls.fail(id,prepared.permit(),code);
                return;
            }
        }
    }
}
