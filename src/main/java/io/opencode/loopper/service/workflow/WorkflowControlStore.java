package io.opencode.loopper.service.workflow;

import io.opencode.loopper.domain.*;
import io.opencode.loopper.lifecycle.LifecycleTransitionService;
import io.opencode.loopper.persistence.*;
import io.opencode.loopper.persistence.WorkflowControlMapper.*;
import io.opencode.loopper.service.*;
import io.opencode.loopper.workflow.*;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import static io.opencode.loopper.service.workflow.WorkflowCommands.conflict;

/** Durable dispatch authority. Admission rechecks its version after filesystem preflight. */
@Service
@Transactional(readOnly=true)
public class WorkflowControlStore {
    private final WorkflowControlMapper mapper;
    private final WorkflowPlanMapper plans;
    private final WorkflowPlanCandidateMapper candidates;
    private final LifecycleTransitionService lifecycle;
    public WorkflowControlStore(WorkflowControlMapper mapper,WorkflowPlanMapper plans,LifecycleTransitionService lifecycle,WorkflowPlanCandidateMapper candidates) {
        this.mapper=mapper;this.plans=plans;this.lifecycle=lifecycle;this.candidates=candidates;
    }
    public Optional<Control> find(String id) { return mapper.find(id); }
    public List<Checkpoint> pending(String id) { return mapper.pending(id); }
    public static WorkflowDispatch.Scope scope(Control row) {
        return new WorkflowDispatch.Scope(WorkflowDispatch.Mode.valueOf(row.mode()),row.targetKey(),row.manualRetryNode(),row.manualRetryOrdinal());
    }
    @Transactional(propagation=Propagation.MANDATORY)
    public Control configure(Control desired) {
        var old=find(desired.requirementId());
        if (old.isEmpty()) {
            lifecycle.create(subject(desired.requirementId()),desired.state(),Map.of("mode",desired.mode(),"planRevision",desired.planRevision()),
                    ()->mapper.insert(desired),WorkflowCommands::conflict);
        } else {
            if (old.get().version()!=desired.version() || mapper.configure(desired)!=1) throw conflict();
            state(desired.requirementId(),WorkflowControlState.ACTIVE,null);
        }
        return find(desired.requirementId()).orElseThrow(WorkflowCommands::conflict);
    }
    @Transactional(propagation=Propagation.MANDATORY)
    public void state(String id,WorkflowControlState next,String reason) { state(id,next,reason,false); }
    @Transactional(propagation=Propagation.MANDATORY)
    public void state(String id,WorkflowControlState next,String reason,boolean force) {
        var row=find(id).orElseThrow(WorkflowCommands::conflict);
        if (!force && row.state().equals(next.name()) && Objects.equals(reason,row.reasonCode())) return;
        var event=row.state().equals(next.name())?LifecycleEvent.UPDATE:switch(next) {
            case ACTIVE -> LifecycleEvent.RESUME;case PAUSED -> LifecycleEvent.PAUSE;
            case WAITING -> LifecycleEvent.REQUIRE_INPUT;case STALLED -> LifecycleEvent.FAIL;case DONE -> LifecycleEvent.COMPLETE;
        };
        lifecycle.transition(subject(id),row.state(),next.name(),event,reason,Map.of("mode",row.mode()),
                ()->mapper.transition(id,row.version(),row.state(),next.name(),reason,Instant.now().toString()),WorkflowCommands::conflict);
    }
    public void requireReviewed(String id) {
        if(candidates.pending(id))throw new ConflictException("WORKFLOW_PLAN_REVIEW_REQUIRED","有候选计划等待查看，请确认或退回后再继续执行。");
    }
    @Transactional(propagation=Propagation.MANDATORY)
    public void replan(String id,int revision,WorkflowGraph graph) {
        var row=find(id).orElseThrow(WorkflowCommands::conflict);
        // A removed target retains its historical scope, paused, until a new explicit Start chooses a scope.
        // Binding that scope to the new plan would either break its foreign key or silently broaden authorization.
        int scopeRevision=row.targetKey()==null || graph.nodes().stream().anyMatch(node->node.id().equals(row.targetKey()))?revision:row.planRevision();
        if(mapper.configure(new Control(id,scopeRevision,row.mode(),row.targetKey(),row.modelJson(),null,0,row.state(),row.reasonCode(),row.version(),row.createdAt(),Instant.now().toString()))!=1)throw conflict();
        state(id,WorkflowControlState.PAUSED,"WORKFLOW_PLAN_CHANGED");
    }
    @Transactional(propagation=Propagation.MANDATORY)
    public void acknowledge(String id,List<String> expected) {
        var pending=pending(id);var provided=expected==null?List.<String>of():expected;
        if (provided.size()!=new HashSet<>(provided).size() || !new HashSet<>(provided).equals(pending.stream().map(Checkpoint::attemptId).collect(java.util.stream.Collectors.toSet())))
            throw new ConflictException("WORKFLOW_CHECKPOINT_CONFIRMATION_REQUIRED","请查看并明确确认当前全部人工检查点，再继续执行");
        for(var check:pending) if(mapper.acknowledge(id,check.attemptId(),Instant.now().toString())!=1) throw conflict();
    }
    @Transactional(propagation=Propagation.MANDATORY)
    public void checkpoint(String id,WorkflowExecutionRows.Node node,WorkflowExecutionRows.Attempt attempt,WorkflowGraph.Node definition) {
        if (!node.requirementId().equals(id) || !attempt.nodeRunId().equals(node.id())) throw conflict();
        if (!attempt.state().equals("SUCCEEDED") || definition.kind()==WorkflowGraph.NodeKind.HUMAN || !definition.pauseAfter()
                || mapper.checkpoint(attempt.id()).isPresent()) return;
        if (mapper.insertCheckpoint(attempt.id(),id,node.nodeKey(),Instant.now().toString())!=1) throw conflict();
        var row=find(id).orElseThrow(WorkflowCommands::conflict);
        var next=Set.of("ACTIVE","WAITING").contains(row.state())?WorkflowControlState.WAITING:WorkflowControlState.valueOf(row.state());
        state(id,next,next==WorkflowControlState.WAITING?"WORKFLOW_CHECKPOINT":row.reasonCode(),true);
    }
    @Transactional(propagation=Propagation.MANDATORY)
    public void authorize(WorkflowRows.Requirement owner,WorkflowGraph graph,Map<String,WorkflowDispatch.Progress> progress,String key,WorkflowDispatch.Permit permit) {
        requireReviewed(owner.id());
        var saved=find(owner.id());
        if (saved.isEmpty() && permit==null) return;
        if (permit==null) throw new ConflictException("WORKFLOW_CONTROL_REQUIRED","该需求已使用画布执行控制，请选择单步、运行至节点或连续执行");
        var row=saved.orElseThrow(WorkflowCommands::conflict);
        if (!row.state().equals("ACTIVE") || row.version()!=permit.controlVersion() || row.planRevision()!=permit.planRevision()
                || row.planRevision()!=owner.headRevision() || !pending(owner.id()).isEmpty()
                || !Objects.equals(key,WorkflowDispatch.next(graph,scope(row),progress).nodeKey())) throw conflict();
    }
    private LifecycleTransitionService.Subject subject(String id) {
        var owner=plans.find(id).orElseThrow(WorkflowCommands::conflict);
        return new LifecycleTransitionService.Subject(LifecycleMachineType.WORKFLOW_CONTROL,id,LifecycleScopeType.PROJECT,owner.projectId());
    }
}
