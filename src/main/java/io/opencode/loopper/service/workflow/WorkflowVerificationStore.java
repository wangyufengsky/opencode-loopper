package io.opencode.loopper.service.workflow;

import io.opencode.loopper.domain.LifecycleEvent;
import io.opencode.loopper.persistence.WorkflowExecutionRows.Attempt;
import io.opencode.loopper.service.*;
import io.opencode.loopper.workflow.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Verification uses the same node ledger, but never a model role or a synthetic Task. */
@Service
@Transactional(readOnly=true)
public class WorkflowVerificationStore {
    private final WorkflowNodeActions actions;
    private final WorkflowNodeRuns nodes;
    private final WorkflowEncoding encoding;
    private final WorkflowCommands commands;
    private final WorkflowSettlement settlement;
    private final WorkflowPlans plans;
    public WorkflowVerificationStore(WorkflowNodeActions actions,WorkflowNodeRuns nodes,WorkflowEncoding encoding,WorkflowCommands commands,WorkflowSettlement settlement,WorkflowPlans plans) {
        this.actions=actions;this.nodes=nodes;this.encoding=encoding;this.commands=commands;this.settlement=settlement;this.plans=plans;
    }
    public record Context(Attempt attempt,WorkflowGraph.Node node,WorkflowVerification spec,WorkflowDelivery.Input input,String requirement,String project) { }
    @Transactional
    public WorkflowNodeActions.Receipt dispatch(String id,String key,WorkflowNodeActions.Start request,WorkflowDispatch.Permit permit) {
        String digest=encoding.digest("NODE_FILE_VERIFY_START",id+"/"+key,request);
        var replay=commands.replay(request.requestKey(),digest,WorkflowNodeActions.Receipt.class);if(replay.isPresent())return replay.get();
        var admission=actions.admit(id,key,request,permit);contract(admission.definition());
        var attempt=nodes.begin(admission.node(),admission.owner().headRevision(),admission.inputs(),WorkflowVerification.ADAPTER,null);
        nodes.transition(attempt,WorkflowAttemptState.RUNNING,LifecycleEvent.START);
        return actions.acknowledge(request.requestKey(),digest,"FILE_VERIFY_START",id,key,attempt.id());
    }
    public Context context(String id) {
        var attempt=nodes.attempt(id);if(WorkflowAttemptState.valueOf(attempt.state()).terminal())return null;
        if(!attempt.adapterKey().equals(WorkflowVerification.ADAPTER) || !attempt.state().equals("RUNNING"))throw WorkflowCommands.conflict();
        nodes.active(attempt);var node=nodes.requireNode(attempt.nodeRunId());var definition=nodes.definition(node);var spec=contract(definition);
        var input=nodes.inputs(attempt).values().stream().filter(value->value.name().equals(spec.inputName())).findFirst().orElseThrow(WorkflowCommands::conflict);
        var owner=plans.require(node.requirementId());return new Context(attempt,definition,spec,input,owner.id(),owner.projectId());
    }
    @Transactional
    public void finish(Context context,WorkflowDelivery delivery,boolean error) {
        var attempt=nodes.attempt(context.attempt().id());if(WorkflowAttemptState.valueOf(attempt.state()).terminal())return;
        nodes.active(context.attempt());nodes.accept(attempt,delivery);nodes.stopVerification(attempt);
        boolean success=!error && switch(context.node().completion().kind()) {
            case VERIFIED -> "PASS".equals(delivery.outcome());
            case OUTCOME -> Objects.equals(context.node().completion().expectedOutcome(),delivery.outcome());
            case DELIVERABLES -> true;
            default -> false;
        };
        nodes.finish(attempt,success?WorkflowAttemptState.SUCCEEDED:WorkflowAttemptState.FAILED);
        settlement.settle(context.requirement(),!success,attempt.id());
    }
    public WorkflowVerification contract(WorkflowGraph.Node node) {
        try {
            if(node.kind()!=WorkflowGraph.NodeKind.SYSTEM || !WorkflowVerification.MODULE.equals(node.moduleId()) || node.moduleVersion()!=1
                    || node.roleId()!=null || node.roleRevisionId()!=null || node.completion()==null
                    || !Set.of(WorkflowGraph.CompletionKind.VERIFIED,WorkflowGraph.CompletionKind.OUTCOME,WorkflowGraph.CompletionKind.DELIVERABLES).contains(node.completion().kind())
                    || !new HashSet<>(node.outcomes()).equals(Set.of("PASS","FAIL")) || node.outcomes().size()!=2
                    || node.outputs().size()!=2 || node.outputs().stream().noneMatch(o->o.name().equals("report")&&o.kind()==WorkflowGraph.DataKind.JSON&&o.required())
                    || node.outputs().stream().noneMatch(o->o.name().equals("summary")&&o.kind()==WorkflowGraph.DataKind.TEXT&&o.required()))throw new IllegalArgumentException("交付物检查需要系统节点、检查报告及说明，并保留通过/未通过两个结果。");
            String body=node.parameters().get("verification");var value=encoding.decode(body,tools.jackson.databind.JsonNode.class);
            if(!value.isObject() || !value.propertyNames().equals(Set.of("version","inputName","checks")) || !value.path("version").isIntegralNumber()
                    || !value.path("inputName").isString() || !value.path("checks").isArray())throw new IllegalArgumentException("检查配置需要明确版本、输入名称和检查项目。");
            for(var check:value.path("checks"))if(!check.isObject() || !Set.of("title","type","path","expected","matchMode").containsAll(check.propertyNames())
                    || !check.path("title").isString() || !check.path("type").isString() || !check.path("path").isString()
                    || check.hasNonNull("expected") && !check.path("expected").isString() || check.hasNonNull("matchMode") && !check.path("matchMode").isString())
                throw new IllegalArgumentException("检查项目包含无效类型或未支持的字段。");
            var spec=encoding.decode(body,WorkflowVerification.class);spec.validate();
            if(node.inputs().stream().noneMatch(input->input.name().equals(spec.inputName()) && input.source()==WorkflowGraph.InputSource.NODE
                    && input.kind()==WorkflowGraph.DataKind.CODE && input.required()))throw new IllegalArgumentException("请选择必需的上游代码交付作为检查输入。");
            return spec;
        }catch(RuntimeException invalid){throw new BadRequestException("WORKFLOW_VERIFICATION_INVALID",invalid instanceof IllegalArgumentException?invalid.getMessage():"检查配置无效，请重新设置检查项目。");}
    }
}
