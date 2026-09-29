package io.opencode.loopper.service.workflow;

import io.opencode.loopper.service.BadRequestException;
import io.opencode.loopper.workflow.*;
import java.util.*;
import tools.jackson.databind.JsonNode;

/** Review opinions and their server-bound evidence identity are separate from final acceptance. */
public final class WorkflowReviewContract {
    public static final String REQUIREMENT="review.requirement", RISK="review.risk", GATE="system.review.dual", ADAPTER="system.review.dual.v1";
    private static final Map<String,WorkflowGraph.DataKind> INPUTS=Map.of("design",WorkflowGraph.DataKind.TEXT,"code",WorkflowGraph.DataKind.CODE,"verification",WorkflowGraph.DataKind.JSON);
    private WorkflowReviewContract() { }
    public static boolean reviewer(String module){return REQUIREMENT.equals(module)||RISK.equals(module);}
    public static String perspective(String module){return REQUIREMENT.equals(module)?"REQUIREMENT":RISK.equals(module)?"RISK":null;}
    public static void requireNode(WorkflowGraph.Node node) {
        if(!reviewer(node.moduleId()) || node.kind()!=WorkflowGraph.NodeKind.WORK || node.moduleVersion()!=1
                || node.outputs().size()!=1 || !output(node,"review",WorkflowGraph.DataKind.DECISION)
                || !new HashSet<>(node.outcomes()).equals(Set.of("PASS","BLOCKED")) || node.outcomes().size()!=2
                || node.completion()==null || !Set.of(WorkflowGraph.CompletionKind.DELIVERABLES,WorkflowGraph.CompletionKind.OUTCOME).contains(node.completion().kind()))throw invalid();
        requireInputs(node,INPUTS);
    }
    public static void requireGate(WorkflowGraph.Node node) {
        if(!GATE.equals(node.moduleId()) || node.moduleVersion()!=1 || node.kind()!=WorkflowGraph.NodeKind.SYSTEM || node.roleId()!=null || node.roleRevisionId()!=null
                || node.outputs().size()!=2 || !output(node,"report",WorkflowGraph.DataKind.JSON) || !output(node,"summary",WorkflowGraph.DataKind.TEXT)
                || !new HashSet<>(node.outcomes()).equals(Set.of("PASS","FAIL")) || node.outcomes().size()!=2 || node.completion()==null
                || !Set.of(WorkflowGraph.CompletionKind.VERIFIED,WorkflowGraph.CompletionKind.DELIVERABLES,WorkflowGraph.CompletionKind.OUTCOME).contains(node.completion().kind()))throw invalid();
        requireInputs(node,Map.of("requirementReview",WorkflowGraph.DataKind.DECISION,"riskReview",WorkflowGraph.DataKind.DECISION));
    }
    private static void requireInputs(WorkflowGraph.Node node,Map<String,WorkflowGraph.DataKind> expected) {
        if(node.inputs().size()!=expected.size() || node.inputs().stream().anyMatch(input->input.source()!=WorkflowGraph.InputSource.NODE || !input.required() || input.kind()!=expected.get(input.name()))
                || node.inputs().stream().map(WorkflowGraph.Input::name).distinct().count()!=expected.size())throw invalid();
    }
    private static boolean output(WorkflowGraph.Node node,String name,WorkflowGraph.DataKind kind){return node.outputs().stream().anyMatch(value->value.name().equals(name)&&value.kind()==kind&&value.required());}
    public static WorkflowDelivery accept(WorkflowGraph.Node node,WorkflowDelivery candidate,WorkflowDelivery.Inputs inputs,WorkflowEncoding encoding) {
        requireNode(node);var value=candidate.outputs().get("review");if(value==null || value.kind()!=WorkflowGraph.DataKind.DECISION)throw invalid();
        var body=value.content();
        if(!body.isObject() || !body.propertyNames().equals(Set.of("version","verdict","reason","evidence")) || !body.path("version").isIntegralNumber() || body.path("version").asInt()!=1
                || !body.path("verdict").isString() || !Set.of("PASS","BLOCKED").contains(body.path("verdict").asString())
                || !Objects.equals(candidate.outcome(),body.path("verdict").asString()) || !body.path("reason").isString() || body.path("reason").asString().isBlank()
                || body.path("reason").asString().length()>8000 || !body.path("evidence").isArray() || body.path("evidence").size()==0 || body.path("evidence").size()>3)throw invalid();
        var cited=new HashSet<String>();for(var item:body.path("evidence"))if(!item.isString() || !INPUTS.containsKey(item.asString()) || !cited.add(item.asString()))throw invalid();
        if(candidate.outcome().equals("PASS") && !cited.equals(INPUTS.keySet()))throw invalid();
        var record=new LinkedHashMap<String,Object>();record.put("version",1);record.put("type","REVIEW");record.put("perspective",perspective(node.moduleId()));
        record.put("verdict",candidate.outcome());record.put("reason",body.path("reason").asString());record.put("evidence",new TreeSet<>(cited));record.put("basisSha256",basis(inputs,encoding));
        return new WorkflowDelivery(candidate.summary(),candidate.outcome(),Map.of("review",new WorkflowDelivery.Value(WorkflowGraph.DataKind.DECISION,json(record,encoding))));
    }
    public static String basis(WorkflowDelivery.Inputs inputs,WorkflowEncoding encoding) {
        if(inputs.values().size()!=3 || inputs.values().stream().anyMatch(value->value.kind()!=INPUTS.get(value.name()) || !"NODE".equals(value.source()) || value.attemptId()==null)
                || inputs.values().stream().map(WorkflowDelivery.Input::name).distinct().count()!=3)throw invalid();
        return encoding.digest("WORKFLOW_REVIEW_BASIS_V1",inputs.requirementId(),Map.of("objective",inputs.objective(),"inputs",inputs.values().stream().sorted(Comparator.comparing(WorkflowDelivery.Input::name)).toList()));
    }
    public static Map<String,Object> submission() {
        return Map.of("output","review","content",Map.of("version",1,"verdict","PASS 或 BLOCKED，必须与 delivery.outcome 一致","reason","说明真实依据与未满足项", "evidence",List.of("design","code","verification")),
                "instruction","读取同一批固定设计、代码和程序验证报告后独立判断。PASS 需要引用三项证据；证据不足可提交 BLOCKED。不填写批次哈希、来源身份或程序验收字段，由程序绑定。其他评审员的结论不作为本次依据。");
    }
    static JsonNode json(Object value,WorkflowEncoding encoding){return encoding.decode(encoding.encode(value),JsonNode.class);}
    private static BadRequestException invalid(){return new BadRequestException("WORKFLOW_REVIEW_INVALID","评审需要固定设计、代码和程序验证输入；报告请填写版本、独立结论、原因和已读取的证据名称。");}
}
