package io.opencode.loopper.service.workflow;
import io.opencode.loopper.api.CursorPage;
import io.opencode.loopper.domain.*;
import io.opencode.loopper.lifecycle.LifecycleTransitionService;
import io.opencode.loopper.persistence.WorkflowPlanCandidateMapper;
import io.opencode.loopper.persistence.WorkflowPlanCandidateMapper.*;
import io.opencode.loopper.service.*;
import io.opencode.loopper.workflow.WorkflowPlanCandidateState;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
@Service
@Transactional(readOnly=true)
public class WorkflowPlanCandidateStore {
    private final WorkflowPlanCandidateMapper mapper;
    private final WorkflowPlans plans;
    private final LifecycleTransitionService lifecycle;
    public WorkflowPlanCandidateStore(WorkflowPlanCandidateMapper mapper,WorkflowPlans plans,LifecycleTransitionService lifecycle){this.mapper=mapper;this.plans=plans;this.lifecycle=lifecycle;}
    public Candidate require(String id,String candidate) {
        var row=mapper.find(id,candidate).orElseThrow(()->new NotFoundException("此需求中不存在该候选计划"));
        if(!WorkflowEncoding.hash(row.graphJson()).equals(row.sha256()))throw WorkflowCommands.conflict();return row;
    }
    public Optional<Candidate> byOutput(String attempt,String output){return mapper.byOutput(attempt,output);}
    public CursorPage<Summary> page(String id,String state,String cursor,Integer requested) {
        plans.require(id);if(state!=null && !Set.of("PENDING","APPLIED","REJECTED").contains(state))throw new BadRequestException("WORKFLOW_CANDIDATE_STATE_INVALID","请选择有效的候选计划状态。");
        int limit=PageCursor.limit(requested);var after=PageCursor.decode(cursor);var found=mapper.page(id,state,after==null?null:after.value(),after==null?null:after.id(),limit+1);
        var items=found.stream().limit(limit).toList();var last=items.isEmpty()?null:items.getLast();return new CursorPage<>(items,found.size()>limit?new PageCursor(last.createdAt(),last.id()).encode():null);
    }
    @Transactional(propagation=Propagation.MANDATORY)
    public void create(Candidate row) { lifecycle.create(subject(row),row.state(),Map.of("sourceAttempt",row.attemptId(),"baseRevision",row.baseRevision()),()->mapper.insert(row),WorkflowCommands::conflict); }
    @Transactional(propagation=Propagation.MANDATORY)
    public void decide(Candidate row,WorkflowPlanCandidateState state,Integer revision,String reason) {
        lifecycle.transition(subject(row),row.state(),state.name(),state==WorkflowPlanCandidateState.APPLIED?LifecycleEvent.APPLY:LifecycleEvent.REJECT,null,Map.of("sourceAttempt",row.attemptId()),
                ()->mapper.decide(row.id(),row.version(),state.name(),revision,reason,Instant.now().toString()),WorkflowCommands::conflict);
    }
    private LifecycleTransitionService.Subject subject(Candidate row){return new LifecycleTransitionService.Subject(LifecycleMachineType.WORKFLOW_PLAN_CANDIDATE,row.id(),LifecycleScopeType.PROJECT,plans.require(row.requirementId()).projectId());}
}
