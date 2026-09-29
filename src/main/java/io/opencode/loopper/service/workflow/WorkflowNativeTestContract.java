package io.opencode.loopper.service.workflow;

import io.opencode.loopper.persistence.*;
import io.opencode.loopper.runtime.DurableCommandProtocol.Result;
import io.opencode.loopper.service.BadRequestException;
import io.opencode.loopper.template.SourceTestProfile;
import io.opencode.loopper.workflow.*;
import java.util.*;
import org.springframework.stereotype.Component;

/** A native test must consume the exact successful specialized writer and its source/profile/design. */
@Component
public final class WorkflowNativeTestContract {
    private final WorkflowTestInputs inputs;
    private final WorkflowExecutionMapper nodes;
    private final WorkflowInputSnapshots snapshots;
    private final WorkflowTestScopeMapper scopes;
    private final WorkflowCodeMapper codes;
    private final WorkflowEncoding encoding;
    private final WorkflowNativeTestEvidence evidence;
    private final WorkflowTestCodeLineage lineage;
    public WorkflowNativeTestContract(WorkflowTestInputs inputs,WorkflowExecutionMapper nodes,WorkflowInputSnapshots snapshots,WorkflowTestScopeMapper scopes,WorkflowCodeMapper codes,WorkflowEncoding encoding,WorkflowNativeTestEvidence evidence,WorkflowTestCodeLineage lineage){this.inputs=inputs;this.nodes=nodes;this.snapshots=snapshots;this.scopes=scopes;this.codes=codes;this.encoding=encoding;this.evidence=evidence;this.lineage=lineage;}
    public record Batch(WorkflowDelivery.Input input,WorkflowTestDesign.Frozen design,String writerAttempt){ }
    public record Context(WorkflowTestInputs.Context fixed,WorkflowTestDesign.Frozen design,WorkflowDelivery.Input code,SourceTestProfile.Module module,WorkflowCommandVerification spec,List<Batch> batches,List<String> writerLineage){ }
    public Context resolve(WorkflowGraph.Node node,WorkflowDelivery.Inputs values) {
        WorkflowNativeTest.Settings settings;try{settings=WorkflowNativeTest.require(node);}catch(IllegalArgumentException e){throw invalid();}
        if(node.moduleVersion()==2)return resolveFinal(settings,values);
        var fixed=inputs.require(values);var design=inputs.design(values,fixed);var code=named(values,"code");
        var producer=nodes.attempt(code.attemptId()).orElseThrow(WorkflowNativeTestContract::invalid);var owner=nodes.node(producer.nodeRunId()).orElseThrow(WorkflowNativeTestContract::invalid);
        if(!producer.state().equals("SUCCEEDED")||!producer.adapterKey().equals(WorkflowWriterLeases.ADAPTER)||!owner.requirementId().equals(values.requirementId())
                ||!owner.id().equals(code.sourceId())||!"code".equals(code.outputName())||nodes.stop(producer.id()).isEmpty()||!WorkflowEncoding.hash(owner.definitionJson()).equals(owner.definitionSha256()))throw invalid();
        try{WorkflowTestWrite.require(encoding.decode(owner.definitionJson(),WorkflowGraph.Node.class));}catch(IllegalArgumentException e){throw invalid();}
        var original=snapshots.materialize(snapshots.snapshot(producer));for(String name:List.of("source","profile","design"))if(!named(values,name).equals(named(original,name)))throw invalid();
        var saved=nodes.delivery(producer.id()).orElseThrow(WorkflowNativeTestContract::invalid);
        if(!saved.sha256().equals(code.sha256())||!WorkflowEncoding.hash(saved.contentJson()).equals(saved.sha256()))throw invalid();
        var output=encoding.decode(saved.contentJson(),WorkflowDelivery.class).outputs().get("code");if(output==null||output.kind()!=WorkflowGraph.DataKind.CODE||!output.content().equals(code.content()))throw invalid();
        var ref=encoding.decode(encoding.encode(code.content()),WorkflowCodeSnapshot.Reference.class);var snapshot=codes.snapshot(ref.snapshotId()).orElseThrow(WorkflowNativeTestContract::invalid);
        var manifest=codes.manifest(ref.snapshotId()).orElseThrow(WorkflowNativeTestContract::invalid);var scope=scopes.result(producer.id()).orElseThrow(WorkflowNativeTestContract::invalid);
        if(ref.version()!=1||!snapshot.attemptId().equals(producer.id())||!snapshot.requirementId().equals(values.requirementId())||!scope.passed()||!scope.checkpointTree().equals(snapshot.resultTree())
                ||!WorkflowEncoding.hash(scope.reportJson()).equals(scope.reportSha256())||!manifest.sha256().equals(ref.sha256())||!WorkflowEncoding.hash(manifest.contentJson()).equals(ref.sha256()))throw invalid();
        var modules=fixed.frozen().profile().modules().stream().filter(m->m.root().equals(settings.moduleRoot())).toList();if(modules.size()!=1)throw invalid();var module=modules.getFirst();
        if(design.design().scenarios().stream().noneMatch(s->module.sourcePaths().contains(s.path())))throw invalid();
        var spec=new WorkflowCommandVerification(1,"code",WorkflowNativeTest.argv(module),settings.timeoutSeconds(),"TEST",null);spec.validate();
        return new Context(fixed,design,code,module,spec,List.of(new Batch(named(values,"design"),design,code.attemptId())),List.of(code.attemptId()));
    }
    private Context resolveFinal(WorkflowNativeTest.Settings settings,WorkflowDelivery.Inputs values) {
        var fixed=inputs.require(values);var code=named(values,"code");var chain=lineage.trace(values,code);
        var modules=fixed.frozen().profile().modules().stream().filter(m->m.root().equals(settings.moduleRoot())).toList();if(modules.size()!=1)throw invalid();var module=modules.getFirst();
        var batches=new ArrayList<Batch>();var seen=new HashSet<String>();
        for(var input:values.values().stream().filter(i->i.name().equals("design")||i.name().startsWith("design_")).toList()) {
            if(!seen.add(input.attemptId()))throw WorkflowTestCodeLineage.invalid();
            var design=inputs.design(values,input,fixed);
            var writer=chain.stream().filter(w->WorkflowTestCodeLineage.same(w.design(),input)).findFirst().orElseThrow(WorkflowTestCodeLineage::invalid);
            if(design.design().scenarios().stream().noneMatch(s->module.sourcePaths().contains(s.path())))throw invalid();
            batches.add(new Batch(input,design,writer.code().attemptId()));
        }
        if(batches.isEmpty())throw invalid();
        var spec=new WorkflowCommandVerification(1,"code",WorkflowNativeTest.argv(module),settings.timeoutSeconds(),"TEST",null);spec.validate();
        return new Context(fixed,batches.getFirst().design(),code,module,spec,List.copyOf(batches),chain.stream().map(e->e.code().attemptId()).toList());
    }
    public WorkflowCommandContract.Evaluation evaluate(String attempt,WorkflowGraph.Node node,Context context,Result result) {
        var nativeReport=evidence.report(attempt,result);var counts=nativeReport.counts();
        boolean valid=result.launched()&&result.stopConfirmed()&&!result.timedOut()&&!result.cancelled()&&!result.outputTruncated()&&result.error().isEmpty()&&result.exitCode()!=null&&nativeReport.valid();
        boolean passed=valid&&result.exitCode()==0&&counts.failed()==0;
        String summary=passed?"原生单测通过，共执行 "+(counts.passed()+counts.failed())+" 项测试。":!valid?"未取得完整的原生测试证据。":"原生单测未通过，已保留失败结果。";
        var report=new LinkedHashMap<String,Object>();report.put("version",node.moduleVersion());report.put("type",WorkflowNativeTest.TYPE);report.put("passed",passed);report.put("valid",valid);
        report.put("moduleRoot",context.module().root());report.put("framework",context.module().framework());report.put("command",evidence.request(attempt,result).argv());report.put("counts",counts);report.put("message",nativeReport.message());
        report.put("producerAttempt",context.code().attemptId());report.put("inputSha256",context.code().sha256());report.put("code",context.code().content());
        report.put("sourceAttempt",context.fixed().source().attemptId());report.put("source",context.fixed().frozen().source());report.put("profileSha256",context.design().profileSha256());
        if(node.moduleVersion()==1)report.put("scenarioIds",context.design().design().scenarios().stream().filter(s->context.module().sourcePaths().contains(s.path())).map(WorkflowTestDesign.Scenario::key).toList());
        else {
            report.put("writerLineage",context.writerLineage());
            // Full scenario keys remain in each immutable design; repeating them can exceed the report body limit.
            report.put("batches",context.batches().stream().map(b->Map.of("inputName",b.input().name(),"designAttempt",b.input().attemptId(),"designSha256",b.input().sha256(),"writerAttempt",b.writerAttempt(),
                    "scenarioCount",b.design().design().scenarios().stream().filter(s->context.module().sourcePaths().contains(s.path())).count())).toList());
        }
        report.put("scenarioCoverageVerified",false);report.put("exitCode",result.exitCode());
        report.put("inputUnchanged",nativeReport.inputUnchanged());
        report.put("fileCount",nativeReport.files().size());report.put("files",nativeReport.files().stream().limit(20).map(f->Map.of("path",f.path(),"sha256",f.sha256())).toList());
        var delivery=new WorkflowDelivery(summary,passed?"PASS":"FAIL",Map.of("report",new WorkflowDelivery.Value(WorkflowGraph.DataKind.JSON,json(report)),"summary",new WorkflowDelivery.Value(WorkflowGraph.DataKind.TEXT,json(summary))));
        boolean success=valid&&switch(node.completion().kind()){case VERIFIED->passed;case DELIVERABLES->true;case OUTCOME->Objects.equals(node.completion().expectedOutcome(),delivery.outcome());default->false;};
        return new WorkflowCommandContract.Evaluation(delivery,success);
    }
    private tools.jackson.databind.JsonNode json(Object value){return encoding.decode(encoding.encode(value),tools.jackson.databind.JsonNode.class);}
    private static WorkflowDelivery.Input named(WorkflowDelivery.Inputs values,String name){return values.values().stream().filter(v->v.name().equals(name)).findFirst().orElseThrow(WorkflowNativeTestContract::invalid);}
    private static BadRequestException invalid(){return new BadRequestException("WORKFLOW_TEST_RUN_INPUT_INVALID","请选择同一次成功单测编写的代码、源码、配置和场景，并指定配置中包含本批场景的模块路径。");}
}
