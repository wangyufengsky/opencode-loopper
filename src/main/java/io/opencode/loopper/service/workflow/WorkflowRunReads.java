package io.opencode.loopper.service.workflow;

import io.opencode.loopper.api.CursorPage;
import io.opencode.loopper.persistence.WorkflowRunReadMapper;
import io.opencode.loopper.service.*;
import io.opencode.loopper.workflow.WorkflowGraph;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly=true)
public class WorkflowRunReads {
    private final WorkflowRunReadMapper mapper;
    private final WorkflowPlans plans;
    private final WorkflowNodeRuns nodes;
    private final WorkflowControls controls;
    private final WorkflowNodeActions actions;
    public WorkflowRunReads(WorkflowRunReadMapper mapper,WorkflowPlans plans,WorkflowNodeRuns nodes,WorkflowControls controls,WorkflowNodeActions actions){this.mapper=mapper;this.plans=plans;this.nodes=nodes;this.controls=controls;this.actions=actions;}
    public record Snapshot(WorkflowControls.View control,WorkflowNodeActions.Overview execution) { }
    public Snapshot snapshot(String id){return new Snapshot(controls.get(id),actions.overview(id));}
    public CursorPage<WorkflowRunReadMapper.Attempt> page(String id,String key,String cursor,Integer requested) {
        plans.require(id);int limit=PageCursor.limit(requested);var after=PageCursor.decode(cursor);
        var found=mapper.page(id,key,after==null?null:after.value(),after==null?null:after.id(),limit+1);
        var items=found.stream().limit(limit).toList();var last=items.isEmpty()?null:items.getLast();
        return new CursorPage<>(items,found.size()>limit?new PageCursor(last.createdAt(),last.id()).encode():null);
    }
    public WorkflowRunReadMapper.Attempt get(String id,String key,String attempt) {
        return mapper.find(id,key,attempt).orElseThrow(()->new NotFoundException("该需求节点中不存在此执行尝试"));
    }
    public WorkflowGraph.Node definition(String id,String key,String attempt) {
        return nodes.definition(mapper.definition(id,key,attempt).orElseThrow(()->new NotFoundException("该需求节点中不存在此执行尝试")));
    }
}
