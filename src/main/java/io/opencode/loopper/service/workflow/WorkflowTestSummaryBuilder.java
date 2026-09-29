package io.opencode.loopper.service.workflow;

import io.opencode.loopper.runtime.WorkflowModelProfile;
import io.opencode.loopper.service.BadRequestException;
import io.opencode.loopper.workflow.*;
import java.util.*;
import org.springframework.stereotype.Component;

/** Verifies the complete source scope against actual final-code tests and independent fixed opinions. */
@Component
public final class WorkflowTestSummaryBuilder {
    private final WorkflowTestInputs inputs;
    private final WorkflowNativeTestContract tests;
    private final WorkflowNodeRuns nodes;
    private final WorkflowEncoding encoding;
    public WorkflowTestSummaryBuilder(WorkflowTestInputs inputs,WorkflowNativeTestContract tests,WorkflowNodeRuns nodes,WorkflowEncoding encoding){this.inputs=inputs;this.tests=tests;this.nodes=nodes;this.encoding=encoding;}
    private record Test(WorkflowDelivery.Input input,WorkflowNativeTestContract.Context context){ }
    private record Opinion(String attempt,String session,String role,String basis,String verdict){ }
    public record Result(WorkflowDelivery delivery,boolean passed){ }
    public Result build(WorkflowGraph.Node node,WorkflowDelivery.Inputs values) {
        WorkflowTestSummary.ReviewPolicy policy;try{policy=WorkflowTestSummary.require(node);}catch(IllegalArgumentException failure){throw invalid(failure.getMessage());}
        var fixed=inputs.require(values);var code=named(values,"code");var runs=new LinkedHashMap<String,Test>();var roots=new HashSet<String>();
        for(var input:values.values())if(input.name().startsWith("test_")) {
            var definition=producer(values,input,WorkflowCommandVerification.ADAPTER,"report");
            if(!WorkflowNativeTest.MODULE.equals(definition.moduleId())||definition.moduleVersion()!=2)throw invalid("汇总需要各模块对最终代码执行的 V2 原生测试报告。");
            var original=nodes.inputs(nodes.attempt(input.attemptId()));same(values,original,List.of("source","profile","code"));
            var context=tests.resolve(definition,original);
            if(!roots.add(context.module().root())||runs.putIfAbsent(input.attemptId(),new Test(input,context))!=null)throw invalid("每个模块请选择一次明确的最终代码测试，不能重复累计旧报告。");
        }
        var opinions=new LinkedHashMap<String,List<Opinion>>();var seen=new HashSet<String>();
        for(var input:values.values())if(input.name().startsWith("review_")) {
            if(!seen.add(input.attemptId()))throw invalid("同一评审不能重复计数。");
            var definition=producer(values,input,WorkflowModelProfile.ADAPTER,"review");
            try{WorkflowTestReview.require(definition);}catch(IllegalArgumentException failure){throw invalid("请选择专业单测场景复核。");}
            if(definition.moduleVersion()!=2)throw invalid("最终代码汇总需要 V2 场景复核。");
            var original=nodes.inputs(nodes.attempt(input.attemptId()));same(values,original,List.of("source","profile","code"));
            var test=named(original,"test");var run=runs.get(test.attemptId());
            if(run==null||!WorkflowTestCodeLineage.same(test,run.input()))throw invalid("复核引用的测试不属于本次最终回归。");
            var design=named(original,"design");
            if(run.context().batches().stream().noneMatch(b->WorkflowTestCodeLineage.same(b.input(),design)))throw invalid("复核引用的设计不属于该模块的固定批次。");
            String basis=WorkflowTestReviewContract.basis(original,encoding);var body=input.content();String verdict=body.path("verdict").asString();
            if(!WorkflowTestReview.TYPE.equals(body.path("type").asString())||!basis.equals(body.path("basisSha256").asString())||!Set.of("PASS","REVISE").contains(verdict)
                    ||!test.attemptId().equals(body.path("testAttempt").asString())||!design.attemptId().equals(body.path("designAttempt").asString())||!code.attemptId().equals(body.path("writerAttempt").asString()))throw invalid("复核来源与接受记录不一致。");
            var attempt=nodes.attempt(input.attemptId());
            opinions.computeIfAbsent(pair(test.attemptId(),design.attemptId()),ignored->new ArrayList<>()).add(new Opinion(attempt.id(),attempt.externalSessionId(),definition.roleId(),basis,verdict));
        }
        var covered=new HashSet<String>();var batches=new ArrayList<Map<String,Object>>();var modules=new ArrayList<Map<String,Object>>();
        boolean testPassed=true,reviewSatisfied=true;int reviewed=0;
        for(var run:runs.values()) {
            var body=run.input().content();boolean passed=body.path("valid").asBoolean(false)&&body.path("passed").asBoolean(false)&&body.path("inputUnchanged").asBoolean(false);testPassed&=passed;
            modules.add(Map.of("root",run.context().module().root(),"framework",run.context().module().framework(),"testAttempt",run.input().attemptId(),"passed",passed,"counts",body.path("counts")));
            for(var batch:run.context().batches()) {
                var scenarios=batch.design().design().scenarios().stream().filter(s->run.context().module().sourcePaths().contains(s.path())).toList();scenarios.forEach(s->covered.add(s.path()));
                var selected=opinions.getOrDefault(pair(run.input().attemptId(),batch.input().attemptId()),List.of());
                if(selected.size()>2)throw invalid("每批最多选择两份明确独立复核，不能混合多次意见。");
                boolean reviewedBatch=independent(selected,policy);reviewSatisfied&=reviewedBatch;if(!selected.isEmpty())reviewed++;
                batches.add(Map.of("moduleRoot",run.context().module().root(),"designAttempt",batch.input().attemptId(),"scenarioCount",scenarios.size(),"reviewSatisfied",reviewedBatch,"reviews",selected));
            }
        }
        var expected=fixed.manifest().files().stream().filter(io.opencode.loopper.template.SourceManifest.File::processable).map(io.opencode.loopper.template.SourceManifest.File::path).collect(java.util.stream.Collectors.toSet());
        var expectedModules=fixed.frozen().profile().modules().stream().map(io.opencode.loopper.template.SourceTestProfile.Module::root).collect(java.util.stream.Collectors.toSet());
        boolean complete=covered.equals(expected)&&roots.equals(expectedModules),passed=complete&&testPassed&&reviewSatisfied;
        var report=new LinkedHashMap<String,Object>();report.put("version",1);report.put("type",WorkflowTestSummary.TYPE);report.put("complete",complete);report.put("passed",passed);report.put("testPassed",testPassed);
        report.put("sourceCount",expected.size());report.put("coveredSourceCount",covered.size());report.put("moduleCount",expectedModules.size());report.put("modules",modules);report.put("batchCount",batches.size());report.put("batches",batches);
        report.put("reviewPolicy",policy.name());report.put("reviewSatisfied",reviewSatisfied);report.put("reviewedBatchCount",reviewed);report.put("code",code.content());report.put("writerAttempt",code.attemptId());
        String summary=passed?"固定范围内的全部模块已在同一份最终代码上完成测试，并满足所选复核策略。":"单测汇总未通过，请检查源码覆盖、各模块实际测试和所选复核策略。";
        return new Result(new WorkflowDelivery(summary,passed?"PASS":"FAIL",Map.of("report",value(WorkflowGraph.DataKind.JSON,report),"summary",value(WorkflowGraph.DataKind.TEXT,summary))),passed);
    }
    private boolean independent(List<Opinion> values,WorkflowTestSummary.ReviewPolicy policy) {
        if(policy==WorkflowTestSummary.ReviewPolicy.NONE)return true;
        if(values.size()!=(policy==WorkflowTestSummary.ReviewPolicy.DUAL?2:1)||values.stream().anyMatch(o->!o.verdict().equals("PASS")))return false;
        if(values.size()==1)return true;var a=values.getFirst();var b=values.getLast();
        return a.basis().equals(b.basis())&&!Objects.equals(a.role(),b.role())&&a.session()!=null&&b.session()!=null&&!a.session().equals(b.session());
    }
    private WorkflowGraph.Node producer(WorkflowDelivery.Inputs values,WorkflowDelivery.Input input,String adapter,String output) {
        if(!"NODE".equals(input.source())||input.attemptId()==null||!output.equals(input.outputName()))throw invalid("请选择本需求已完成节点的固定交付物。");
        var attempt=nodes.attempt(input.attemptId());var owner=nodes.requireNode(attempt.nodeRunId());
        if(!owner.requirementId().equals(values.requirementId())||!owner.id().equals(input.sourceId())||!attempt.state().equals("SUCCEEDED")||!attempt.adapterKey().equals(adapter)||!nodes.hasStop(attempt.id()))throw invalid("交付物生产者尚未成功收尾或不属于本需求。");
        var saved=nodes.delivery(attempt.id());if(!input.sha256().equals(saved.sha256())||!WorkflowEncoding.hash(saved.contentJson()).equals(saved.sha256()))throw invalid("固定交付物摘要不匹配。");
        var accepted=encoding.decode(saved.contentJson(),WorkflowDelivery.class).outputs().get(output);
        if(accepted==null||accepted.kind()!=input.kind()||!accepted.content().equals(input.content()))throw invalid("交付内容与接受版本不一致。");
        return nodes.definition(owner);
    }
    private void same(WorkflowDelivery.Inputs summary,WorkflowDelivery.Inputs original,List<String> names){for(String name:names)if(!WorkflowTestCodeLineage.same(named(summary,name),named(original,name)))throw invalid("全部模块测试和复核必须绑定同一份源码、配置与最终代码。");}
    private static String pair(String test,String design){return test+":"+design;}
    private static WorkflowDelivery.Input named(WorkflowDelivery.Inputs values,String name){return values.values().stream().filter(i->i.name().equals(name)).findFirst().orElseThrow(()->invalid("缺少必需的固定输入。"));}
    private WorkflowDelivery.Value value(WorkflowGraph.DataKind kind,Object body){return new WorkflowDelivery.Value(kind,encoding.decode(encoding.encode(body),tools.jackson.databind.JsonNode.class));}
    static BadRequestException invalid(String message){return new BadRequestException("WORKFLOW_TEST_SUMMARY_INVALID",message);}
}
