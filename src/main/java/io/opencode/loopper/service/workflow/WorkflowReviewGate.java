package io.opencode.loopper.service.workflow;

import io.opencode.loopper.domain.LifecycleEvent;
import io.opencode.loopper.runtime.WorkflowModelProfile;
import io.opencode.loopper.service.*;
import io.opencode.loopper.workflow.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

/** A deterministic, database-only join of two independent opinions on one exact evidence batch. */
@Service
public class WorkflowReviewGate {
    private final WorkflowNodeActions actions;
    private final WorkflowNodeRuns nodes;
    private final WorkflowEncoding encoding;
    private final WorkflowCommands commands;
    private final WorkflowSettlement settlement;
    public WorkflowReviewGate(WorkflowNodeActions actions,WorkflowNodeRuns nodes,WorkflowEncoding encoding,WorkflowCommands commands,WorkflowSettlement settlement) {
        this.actions=actions;this.nodes=nodes;this.encoding=encoding;this.commands=commands;this.settlement=settlement;
    }
    @Transactional
    public WorkflowNodeActions.Receipt dispatch(String id,String key,WorkflowNodeActions.Start request,WorkflowDispatch.Permit permit) {
        String digest=encoding.digest("NODE_REVIEW_GATE_START",id+"/"+key,request);
        var replay=commands.replay(request.requestKey(),digest,WorkflowNodeActions.Receipt.class);if(replay.isPresent())return replay.get();
        var admission=actions.admit(id,key,request,permit);var definition=admission.definition();WorkflowReviewContract.requireGate(definition);
        var inputs=admission.inputs();var requirement=review(id,input(inputs,"requirementReview"),WorkflowReviewContract.REQUIREMENT);
        var risk=review(id,input(inputs,"riskReview"),WorkflowReviewContract.RISK);
        if(!requirement.basis().equals(risk.basis()) || requirement.attempt().equals(risk.attempt()))throw invalid();
        boolean pass=requirement.verified() && requirement.verdict().equals("PASS") && risk.verdict().equals("PASS");
        String summary=pass?"同批程序验证及需求、风险双评审通过。":"同批验收未通过，请查看程序验证与两份独立评审意见。";
        var report=Map.of("version",1,"type","DUAL_REVIEW","basisSha256",requirement.basis(),"passed",pass,"verificationPassed",requirement.verified(),
                "reviews",List.of(requirement,risk));
        var delivery=new WorkflowDelivery(summary,pass?"PASS":"FAIL",Map.of("summary",new WorkflowDelivery.Value(WorkflowGraph.DataKind.TEXT,WorkflowReviewContract.json(summary,encoding)),
                "report",new WorkflowDelivery.Value(WorkflowGraph.DataKind.JSON,WorkflowReviewContract.json(report,encoding))));
        var attempt=nodes.begin(admission.node(),admission.owner().headRevision(),inputs,WorkflowReviewContract.ADAPTER,null);
        attempt=nodes.transition(attempt,WorkflowAttemptState.RUNNING,LifecycleEvent.START);
        nodes.accept(attempt,delivery);nodes.stopReviewGate(attempt);
        boolean success=switch(definition.completion().kind()) {case VERIFIED->pass;case DELIVERABLES->true;case OUTCOME->Objects.equals(definition.completion().expectedOutcome(),delivery.outcome());default->false;};
        nodes.finish(attempt,success?WorkflowAttemptState.SUCCEEDED:WorkflowAttemptState.FAILED);settlement.settle(id,!success,attempt.id());
        return actions.acknowledge(request.requestKey(),digest,"REVIEW_GATE",id,key,attempt.id());
    }
    public record Opinion(String attempt,String perspective,String basis,String verdict,String reason,boolean verified) { }
    private Opinion review(String requirement,WorkflowDelivery.Input input,String module) {
        if(input.kind()!=WorkflowGraph.DataKind.DECISION || !"review".equals(input.outputName()))throw invalid();
        var attempt=nodes.attempt(input.attemptId());var row=nodes.requireNode(attempt.nodeRunId());var definition=nodes.definition(row);
        if(!row.requirementId().equals(requirement) || !attempt.adapterKey().equals(WorkflowModelProfile.ADAPTER) || !attempt.state().equals("SUCCEEDED")
                || !definition.moduleId().equals(module) || !nodes.hasStop(attempt.id()))throw invalid();
        WorkflowReviewContract.requireNode(definition);
        var delivery=nodes.findDelivery(attempt.id()).orElseThrow(WorkflowReviewGate::invalid);
        if(!delivery.sha256().equals(input.sha256()) || !WorkflowEncoding.hash(delivery.contentJson()).equals(delivery.sha256()))throw invalid();
        var accepted=encoding.decode(delivery.contentJson(),WorkflowDelivery.class).outputs().get("review");
        if(accepted==null || !accepted.content().equals(input.content()))throw invalid();
        var body=input.content();var frozen=nodes.inputs(attempt);String basis=WorkflowReviewContract.basis(frozen,encoding);
        if(!body.path("basisSha256").asString().equals(basis) || !body.path("perspective").asString().equals(WorkflowReviewContract.perspective(module))
                || !body.path("type").asString().equals("REVIEW") || !Set.of("PASS","BLOCKED").contains(body.path("verdict").asString()))throw invalid();
        return new Opinion(attempt.id(),WorkflowReviewContract.perspective(module),basis,body.path("verdict").asString(),body.path("reason").asString(),verified(requirement,frozen));
    }
    private boolean verified(String requirement,WorkflowDelivery.Inputs inputs) {
        var code=input(inputs,"code");var report=input(inputs,"verification");
        var attempt=nodes.attempt(report.attemptId());var owner=nodes.requireNode(attempt.nodeRunId());var definition=nodes.definition(owner);
        if(!owner.requirementId().equals(requirement) || !attempt.state().equals("SUCCEEDED") || !nodes.hasStop(attempt.id())
                || !Set.of(WorkflowVerification.ADAPTER,WorkflowCommandVerification.ADAPTER).contains(attempt.adapterKey())
                || definition.kind()!=WorkflowGraph.NodeKind.SYSTEM || !"report".equals(report.outputName()))throw invalid();
        var checked=nodes.inputs(attempt).values().stream().filter(value->value.kind()==WorkflowGraph.DataKind.CODE
                && Objects.equals(value.attemptId(),code.attemptId()) && value.sha256().equals(code.sha256()) && value.content().equals(code.content())).findFirst();
        if(checked.isEmpty())throw invalid();
        var delivery=nodes.findDelivery(attempt.id()).orElseThrow(WorkflowReviewGate::invalid);
        if(!delivery.sha256().equals(report.sha256()) || !WorkflowEncoding.hash(delivery.contentJson()).equals(delivery.sha256()))throw invalid();
        var value=encoding.decode(delivery.contentJson(),WorkflowDelivery.class);var output=value.outputs().get("report");
        if(output==null || !output.content().equals(report.content()))throw invalid();
        JsonNode body=report.content();
        if(!body.path("producerAttempt").asString().equals(code.attemptId()) || !body.path("inputSha256").asString().equals(code.sha256()))throw invalid();
        return "PASS".equals(value.outcome()) && body.path("passed").isBoolean() && body.path("passed").asBoolean()
                && (!attempt.adapterKey().equals(WorkflowCommandVerification.ADAPTER) || body.path("valid").isBoolean() && body.path("valid").asBoolean());
    }
    private static WorkflowDelivery.Input input(WorkflowDelivery.Inputs inputs,String name){return inputs.values().stream().filter(value->value.name().equals(name)).findFirst().orElseThrow(WorkflowReviewGate::invalid);}
    private static ConflictException invalid(){return new ConflictException("WORKFLOW_REVIEW_EVIDENCE_MISMATCH","两份评审必须来自独立评审节点，并引用同一批固定设计、代码和该代码的程序验证报告。请核对输入绑定。");}
}
