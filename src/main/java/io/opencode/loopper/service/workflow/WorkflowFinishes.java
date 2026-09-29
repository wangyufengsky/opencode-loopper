package io.opencode.loopper.service.workflow;

import static io.opencode.loopper.service.workflow.WorkflowCommands.conflict;
import io.opencode.loopper.domain.*;
import io.opencode.loopper.lifecycle.LifecycleTransitionService;
import io.opencode.loopper.persistence.*;
import io.opencode.loopper.persistence.WorkflowFinishMapper.*;
import io.opencode.loopper.service.*;
import io.opencode.loopper.workflow.*;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** User decision and eventual termination are independent durable facts. No external I/O. */
@Service
@Transactional(readOnly=true)
public class WorkflowFinishes {
    private final WorkflowFinishMapper mapper;
    private final WorkflowPlanMapper plans;
    private final WorkflowEncoding encoding;
    private final WorkflowCommands commands;
    private final WorkflowControlStore controls;
    private final LifecycleTransitionService lifecycle;
    public WorkflowFinishes(WorkflowFinishMapper mapper,WorkflowPlanMapper plans,WorkflowEncoding encoding,
            WorkflowCommands commands,WorkflowControlStore controls,LifecycleTransitionService lifecycle) {
        this.mapper=mapper;this.plans=plans;this.encoding=encoding;this.commands=commands;this.controls=controls;this.lifecycle=lifecycle;
    }
    public record Request(String requestKey,long expectedVersion,WorkflowState target,String reason) { }
    public record View(String requirementId,WorkflowState state,long version,Intent intent,Pending pending) { }
    public View get(String id) {
        var owner=owner(id);var intent=mapper.find(id).orElse(null);
        return new View(id,WorkflowState.valueOf(owner.state()),owner.version(),intent,
                intent==null?new Pending(0,0):mapper.remaining(id));
    }
    @Transactional
    public WorkflowCommands.Receipt request(String id,Request request) {
        if(request==null)throw new BadRequestException("WORKFLOW_FINISH_INVALID","请指定结束结果和原因");
        String digest=encoding.digest("REQUIREMENT_FINISH",id,request);
        var replay=commands.replay(request.requestKey(),digest);if(replay.isPresent())return replay.get();
        if(request.target()==null || !request.target().terminal() || request.reason()==null
                || request.reason().isBlank() || request.reason().length()>4000)
            throw new BadRequestException("WORKFLOW_FINISH_INVALID","请选择成功、失败或取消，并填写不超过 4000 字的原因");
        var owner=owner(id);
        if(owner.version()!=request.expectedVersion() || WorkflowState.valueOf(owner.state()).terminal()
                || owner.state().equals("STOPPING") || mapper.find(id).isPresent())throw conflict();
        var intent=new Intent(id,request.target().name(),request.reason().strip(),owner.headRevision(),owner.version(),now(),null);
        transition(owner,WorkflowState.STOPPING,LifecycleEvent.CANCEL,intent);
        if(mapper.insert(intent)!=1)throw conflict();
        if(controls.find(id).isPresent())controls.state(id,WorkflowControlState.PAUSED,"WORKFLOW_FINISHING");
        finalizeReady(id);
        var current=owner(id);
        return commands.record(request.requestKey(),digest,"REQUIREMENT","FINISH",
                new WorkflowCommands.Receipt(id,current.headRevision(),current.version(),current.layoutVersion(),current.state()));
    }
    @Transactional
    public boolean finalizeReady(String id) {
        var intent=mapper.find(id).orElseThrow(WorkflowCommands::conflict);
        if(intent.finalizedAt()!=null)return true;
        var owner=owner(id);
        if(!owner.state().equals("STOPPING"))throw conflict();
        if(!mapper.remaining(id).empty())return false;
        if(controls.find(id).isPresent())controls.state(id,WorkflowControlState.DONE,"WORKFLOW_USER_FINISHED");
        var target=WorkflowState.valueOf(intent.targetState());
        var event=switch(target){case COMPLETED->LifecycleEvent.SUCCEED;case FAILED->LifecycleEvent.FAIL;case CANCELLED->LifecycleEvent.ABORT;default->throw conflict();};
        transition(owner,target,event,intent);
        if(mapper.finalizeIntent(id,now())!=1)throw conflict();
        return true;
    }
    private WorkflowRows.Requirement owner(String id){return plans.find(id).orElseThrow(()->new NotFoundException("需求任务不存在"));}
    private void transition(WorkflowRows.Requirement row,WorkflowState target,LifecycleEvent event,Intent intent) {
        var subject=new LifecycleTransitionService.Subject(LifecycleMachineType.WORKFLOW_REQUIREMENT,row.id(),LifecycleScopeType.PROJECT,row.projectId());
        lifecycle.transition(subject,row.state(),target.name(),event,"WORKFLOW_USER_FINISH",
                Map.of("source","USER","target",intent.targetState(),"planRevision",intent.planRevision()),
                ()->plans.transition(row.id(),row.version(),row.state(),target.name(),now()),WorkflowCommands::conflict);
    }
    private static String now(){return Instant.now().toString();}
}
