package io.opencode.loopper.service.workflow;

import io.opencode.loopper.api.CursorPage;
import io.opencode.loopper.persistence.*;
import io.opencode.loopper.runtime.DurableCommandProtocol.Result;
import io.opencode.loopper.service.*;
import io.opencode.loopper.template.SourceDesign;
import io.opencode.loopper.workflow.*;
import java.util.*;
import org.springframework.stereotype.Component;

/** Authenticates a native run and records an independent assessment without inventing execution facts. */
@Component
public final class WorkflowTestReviewContract {
    private final WorkflowExecutionMapper nodes;
    private final WorkflowInputSnapshots snapshots;
    private final WorkflowNativeTestContract nativeTests;
    private final WorkflowNativeTestEvidence evidence;
    private final WorkflowEncoding encoding;
    private final WorkflowSourceReadMapper reads;
    private final WorkflowCodeMapper codes;
    private final WorkflowCodeContent content;
    public WorkflowTestReviewContract(WorkflowExecutionMapper nodes,WorkflowInputSnapshots snapshots,WorkflowNativeTestContract nativeTests,
            WorkflowNativeTestEvidence evidence,WorkflowEncoding encoding,WorkflowSourceReadMapper reads,WorkflowCodeMapper codes,WorkflowCodeContent content) {
        this.nodes=nodes;this.snapshots=snapshots;this.nativeTests=nativeTests;this.evidence=evidence;this.encoding=encoding;this.reads=reads;this.codes=codes;this.content=content;
    }
    public record Context(WorkflowNativeTestContract.Context original,WorkflowDelivery.Input test,WorkflowNativeTestReports.Report report,
                          String reportSha256,WorkflowCodeSnapshot code,List<WorkflowNativeCases.Case> cases,List<WorkflowTestDesign.Scenario> scenarios){ }
    public Context context(WorkflowGraph.Node node,WorkflowDelivery.Inputs values) {
        try{WorkflowTestReview.require(node);}catch(IllegalArgumentException failure){throw invalid(failure.getMessage());}
        var test=named(values,"test");
        if(test.kind()!=WorkflowGraph.DataKind.JSON||!test.source().equals("NODE")||!"report".equals(test.outputName()))throw invalid("请选择原生测试节点的实际报告。");
        var run=nodes.attempt(test.attemptId()).orElseThrow(WorkflowTestReviewContract::inputs);
        var owner=nodes.node(run.nodeRunId()).orElseThrow(WorkflowTestReviewContract::inputs);
        if(!run.state().equals("SUCCEEDED")||!run.adapterKey().equals(WorkflowCommandVerification.ADAPTER)||!owner.id().equals(test.sourceId())
                ||!owner.requirementId().equals(values.requirementId())||nodes.stop(run.id()).isEmpty()||!WorkflowEncoding.hash(owner.definitionJson()).equals(owner.definitionSha256()))throw inputs();
        var definition=encoding.decode(owner.definitionJson(),WorkflowGraph.Node.class);
        if(definition.moduleVersion()!=node.moduleVersion())throw inputs();
        var original=snapshots.materialize(snapshots.snapshot(run));
        for(String name:List.of("source","profile","code"))if(!named(values,name).equals(named(original,name)))throw inputs();
        if(node.moduleVersion()==1&&!named(values,"design").equals(named(original,"design")))throw inputs();
        var fixed=nativeTests.resolve(definition,original);var delivery=nodes.delivery(run.id()).orElseThrow(WorkflowTestReviewContract::inputs);
        var batch=fixed.batches().stream().filter(b->WorkflowTestCodeLineage.same(b.input(),named(values,"design"))).findFirst().orElseThrow(WorkflowTestReviewContract::inputs);
        if(!delivery.sha256().equals(test.sha256())||!WorkflowEncoding.hash(delivery.contentJson()).equals(delivery.sha256()))throw inputs();
        var saved=encoding.decode(delivery.contentJson(),WorkflowDelivery.class).outputs().get("report");
        if(saved==null||saved.kind()!=WorkflowGraph.DataKind.JSON||!saved.content().equals(test.content()))throw inputs();
        var receipt=evidence.find(run.id()).orElseThrow(WorkflowTestReviewContract::inputs);
        var result=encoding.decode(receipt.resultJson(),Result.class);var report=evidence.report(run.id(),result);var request=evidence.request(run.id(),result);
        if(!result.stopConfirmed()||!report.valid())throw invalid("原生测试证据不完整，不能进行场景覆盖复核。");
        var reference=encoding.decode(encoding.encode(fixed.code().content()),WorkflowCodeSnapshot.Reference.class);
        var manifest=codes.manifest(reference.snapshotId()).orElseThrow(WorkflowTestReviewContract::inputs);
        if(!manifest.sha256().equals(reference.sha256())||!WorkflowEncoding.hash(manifest.contentJson()).equals(manifest.sha256()))throw inputs();
        var code=encoding.decode(manifest.contentJson(),WorkflowCodeSnapshot.class);
        var scenarios=batch.design().design().scenarios().stream().filter(s->fixed.module().sourcePaths().contains(s.path())).toList();
        return new Context(fixed,test,report,receipt.reportSha256(),code,WorkflowNativeCases.read(report,fixed.module(),request.directory(),encoding),scenarios);
    }
    public byte[] file(Context context,String path,String sha256) {
        var file=context.code().files().stream().filter(f->f.path().equals(path)&&f.sha256().equals(sha256)).findFirst().orElseThrow(WorkflowTestReviewContract::inputs);
        return content.read(context.code(),file);
    }
    public CursorPage<WorkflowNativeCases.Case> cases(WorkflowGraph.Node node,WorkflowDelivery.Inputs values,Map<String,Object> args) {
        if(!Boolean.TRUE.equals(args.get("testCases"))||!Set.of("testCases","cursor","limit").containsAll(args.keySet()))throw inputs();
        var context=context(node,values);String cursor=args.containsKey("cursor")?WorkflowModelTools.text(args,"cursor",2048):null;
        var after=PageCursor.decode(cursor);String scope=WorkflowEncoding.hash(context.test().attemptId()+context.reportSha256());
        if(after!=null&&!after.value().equals(scope))throw invalid("测试记录游标不属于本次固定报告，请重新读取。");
        int limit=WorkflowModelTools.integer(args,"limit",50,1,100);
        var found=context.cases().stream().filter(c->after==null||c.id().compareTo(after.id())>0).limit(limit+1L).toList();var items=found.stream().limit(limit).toList();
        return new CursorPage<>(items,found.size()>limit?new PageCursor(scope,items.getLast().id()).encode():null);
    }
    public Map<String,Object> work(WorkflowGraph.Node node,WorkflowDelivery.Inputs values) {
        var context=context(node,values);
        return Map.of("moduleRoot",context.original().module().root(),"paths",context.scenarios().stream().map(WorkflowTestDesign.Scenario::path).distinct().toList(),
                "files",context.original().fixed().manifest().files().stream().filter(f->context.scenarios().stream().anyMatch(s->s.path().equals(f.path()))).toList(),
                "testCount",context.cases().size(),"testCases",Map.of("tool",io.opencode.loopper.runtime.WorkflowModelProfile.WORK,"args",Map.of("testCases",true,"limit",50)),
                "candidate",Map.of("reason","整体复核依据和局限","scenarios","[{key,assessment:COVERED|INSUFFICIENT|MISSING,reason,testIds:[实际测试id],references:[{path,sha256,startLine,endLine,quote}]}]"),
                "instructions","完整读取 profile、design、test 三份固定输入及本模块全部目标源码；分页读取本次 testCases。通过文件 MCP 列出 code 文件，以 name:code、path、sha256、startLine、lineCount 完整读取引用的测试文件。逐场景判断断言是否验证预期，每个 COVERED 映射须关联真实测试记录和匹配测试文件引用；缺失或不充分要如实记录，不伪造测试 id。不把执行通过当作语义覆盖，程序按实际测试和固定输入证明计算最终结论。只提交候选 reason/scenarios，不填写来源身份或执行事实。");
    }
    public WorkflowDelivery accept(WorkflowExecutionRows.Attempt attempt,WorkflowGraph.Node node,WorkflowDelivery.Inputs values,WorkflowDelivery delivery) {
        var context=context(node,values);
        for(String name:List.of("profile","design","test"))requireInputRead(attempt.id(),named(values,name));
        var paths=context.scenarios().stream().map(WorkflowTestDesign.Scenario::path).distinct().toList();
        SourceDesignValidation.fullReads(paths,path->read(attempt.id(),"source",path));
        WorkflowTestReview.Candidate candidate;
        try{candidate=encoding.decode(encoding.encode(delivery.outputs().get("review").content()),WorkflowTestReview.Candidate.class);}
        catch(RuntimeException failure){throw invalid("请提交完整的场景复核结构。");}
        if(candidate==null)throw invalid("请提交完整的场景复核结构。");text(candidate.reason(),4000);
        if(candidate.scenarios()==null||candidate.scenarios().size()!=context.scenarios().size())throw invalid("复核必须逐一包含本模块的全部设计场景。");
        var seen=new HashSet<String>();var rows=new ArrayList<Map<String,Object>>();
        for(var mapping:candidate.scenarios()) {
            if(mapping==null||!seen.add(mapping.key())||context.scenarios().stream().noneMatch(s->s.key().equals(mapping.key())))throw invalid("场景编号重复或不属于本批设计。");
            text(mapping.reason(),4000);if(!Set.of("COVERED","INSUFFICIENT","MISSING").contains(Objects.toString(mapping.assessment(),"")))throw invalid("请明确场景已覆盖、覆盖不足或缺失。");
            if(mapping.testIds()==null||mapping.testIds().size()>16||new HashSet<>(mapping.testIds()).size()!=mapping.testIds().size()||mapping.references()==null||mapping.references().size()>16||mapping.references().stream().anyMatch(r->r==null||r.path()==null))throw invalid("每个场景最多关联 16 项测试和 16 条测试文件引用。");
            var mapped=mapping.testIds().stream().map(id->context.cases().stream().filter(c->c.id().equals(id)).findFirst().orElseThrow(()->invalid("关联测试必须来自本次原生报告，不能使用其他尝试或自拟编号。"))).toList();
            var cited=mapping.references().stream().map(SourceDesign.Reference::path).distinct().toList();
            if(!cited.isEmpty()) {
                if(cited.stream().anyMatch(p->context.code().files().stream().noneMatch(f->f.path().equals(p))))throw inputs();
                SourceDesignValidation.references(mapping.references(),cited,path->read(attempt.id(),"code",path),"/scenarios/references");
                SourceDesignValidation.fullReads(cited,path->read(attempt.id(),"code",path));
            }
            if(mapping.assessment().equals("COVERED")&&(mapped.isEmpty()||mapped.stream().anyMatch(t->cited.stream().noneMatch(p->WorkflowNativeCases.matches(t,p,context.original().module())))))
                throw invalid("已覆盖场景必须关联实际测试，并完整读取和引用相匹配的测试文件。");
            String status=!Boolean.TRUE.equals(context.report().inputUnchanged())?"EVIDENCE_INCOMPLETE":mapping.assessment().equals("MISSING")?"MISSING":
                    !mapping.assessment().equals("COVERED")?"INSUFFICIENT":mapped.stream().anyMatch(t->t.status().equals("FAILED"))?"FAILED":mapped.stream().anyMatch(t->t.status().equals("SKIPPED"))?"NOT_EXECUTED":"COVERED";
            var scenario=context.scenarios().stream().filter(s->s.key().equals(mapping.key())).findFirst().orElseThrow();
            rows.add(Map.of("key",scenario.key(),"title",scenario.title(),"path",scenario.path(),"assessment",mapping.assessment(),"status",status,"reason",mapping.reason(),"tests",mapped,"references",mapping.references()));
        }
        boolean passed=context.test().content().path("passed").asBoolean(false)&&rows.stream().allMatch(r->r.get("status").equals("COVERED"));
        var report=new LinkedHashMap<String,Object>();report.put("version",1);report.put("type",WorkflowTestReview.TYPE);report.put("verdict",passed?"PASS":"REVISE");report.put("reason",candidate.reason());
        report.put("basisSha256",basis(values,encoding));report.put("sourceAttempt",context.original().fixed().source().attemptId());
        report.put("designAttempt",named(values,"design").attemptId());report.put("writerAttempt",context.original().code().attemptId());report.put("testAttempt",context.test().attemptId());
        report.put("nativeReportSha256",context.reportSha256());report.put("nativePassed",context.test().content().path("passed").asBoolean(false));report.put("inputUnchanged",context.report().inputUnchanged());report.put("scenarios",rows);
        var outputs=new LinkedHashMap<>(delivery.outputs());outputs.put("review",new WorkflowDelivery.Value(WorkflowGraph.DataKind.DECISION,encoding.decode(encoding.encode(report),tools.jackson.databind.JsonNode.class)));
        return new WorkflowDelivery(delivery.summary(),passed?"PASS":"REVISE",outputs);
    }
    private List<SourceDesign.Read> read(String attempt,String name,String path){return reads.sources(attempt,name,path).stream().map(r->new SourceDesign.Read(r.sha256(),r.startLine(),r.endLine(),r.totalLines(),r.content())).toList();}
    static String basis(WorkflowDelivery.Inputs values,WorkflowEncoding encoding){return encoding.digest("WORKFLOW_TEST_REVIEW_BASIS_V1",values.requirementId(),Map.of("objective",values.objective(),"inputs",values.values().stream().sorted(Comparator.comparing(WorkflowDelivery.Input::name)).toList()));}
    private void requireInputRead(String attempt,WorkflowDelivery.Input input) {
        String text=encoding.encode(input.content());int next=0;
        for(var read:reads.inputs(attempt,input.name())){if(!read.sha256().equals(WorkflowEncoding.hash(text))||read.totalLength()!=text.length()||read.startOffset()>next)break;next=Math.max(next,read.endOffset());}
        if(next<text.length())throw invalid("请完整读取本次 profile、design 和 test 固定输入，再提交复核。");
    }
    private static WorkflowDelivery.Input named(WorkflowDelivery.Inputs values,String name){return values.values().stream().filter(v->v.name().equals(name)).findFirst().orElseThrow(WorkflowTestReviewContract::inputs);}
    private static void text(String value,int max){if(value==null||value.isBlank()||value.length()>max||value.indexOf('\0')>=0)throw invalid("复核说明不能为空或超过字段长度上限。");}
    private static BadRequestException inputs(){return invalid("请选择同一次成功原生测试的源码、配置、场景、代码和程序报告，不能混用其他执行版本。");}
    static BadRequestException invalid(String message){return new BadRequestException("WORKFLOW_TEST_REVIEW_INVALID",message);}
}
