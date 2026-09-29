package io.opencode.loopper.service.workflow;
import io.opencode.loopper.persistence.*;
import org.springframework.stereotype.Component;
/** Both live Session and native process uncertainty block new dispatch in the same requirement. */
@Component
public final class WorkflowExecutionGuards {
    private final WorkflowModelMapper models;
    private final WorkflowCommandRunMapper commands;
    public WorkflowExecutionGuards(WorkflowModelMapper models,WorkflowCommandRunMapper commands){this.models=models;this.commands=commands;}
    public String recoveryReason(String id){
        if(models.unresolved(id))return "WORKFLOW_MODEL_RECOVERY_REQUIRED";
        return commands.unresolved(id)?"WORKFLOW_COMMAND_RECOVERY_REQUIRED":null;
    }
}
