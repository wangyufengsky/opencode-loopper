package io.opencode.loopper.service.workflow;

import io.opencode.loopper.persistence.WorkflowModelMapper.Launch;
import io.opencode.loopper.runtime.WorkflowModelProfile;
import io.opencode.loopper.workflow.*;
import java.util.*;
import org.springframework.stereotype.Service;
import static io.opencode.loopper.service.workflow.WorkflowModelTools.*;

/** Bounded read projections for the already-authorized node; reading a preset never creates a run. */
@Service
public class WorkflowModelReads {
    private final WorkflowModelStore store;
    private final WorkflowNodeRuns nodes;
    private final WorkflowPlanCandidates plans;
    private final WorkflowNodePresets presets;
    private final WorkflowCodeFiles files;
    private final WorkflowSourceReads sourceDesign;
    private final WorkflowDocumentReads documentReview;
    private final WorkflowHistoryAnalysisContract history;
    private final WorkflowSnapshotContract snapshot;
    private final WorkflowSnapshotReads snapshotReads;
    public WorkflowModelReads(WorkflowModelStore store,WorkflowNodeRuns nodes,WorkflowPlanCandidates plans,WorkflowNodePresets presets,WorkflowCodeFiles files,WorkflowSourceReads sourceDesign,WorkflowDocumentReads documentReview,WorkflowHistoryAnalysisContract history,WorkflowSnapshotContract snapshot,WorkflowSnapshotReads snapshotReads) {
        this.store=store;this.nodes=nodes;this.plans=plans;this.presets=presets;this.files=files;
        this.sourceDesign=sourceDesign;
        this.documentReview=documentReview;this.history=history;this.snapshot=snapshot;this.snapshotReads=snapshotReads;
    }
    Object file(Launch row,String tool,Map<String,Object> args){
        if(WorkflowSnapshotWork.supports(store.definition(row).moduleId()))return snapshotReads.call(row,tool,args);
        if(tool.equals(WorkflowModelProfile.FILE)&&WorkflowDocumentReview.supports(store.definition(row).moduleId())&&("documents".equals(args.get("name"))||"code".equals(args.get("name"))))return documentReview.file(row,args);
        if(tool.equals(WorkflowModelProfile.FILE)&&WorkflowSourceReads.supports(store.definition(row).moduleId())&&( "source".equals(args.get("name"))||WorkflowTestReview.supports(store.definition(row).moduleId())&&"code".equals(args.get("name"))))return sourceDesign.file(row,args);
        return files.model(row,tool,args);
    }
    Object read(Launch row,String tool,Map<String,Object> args) {
        if(tool.equals(WorkflowModelProfile.INPUT))return input(row,args);
        if(!tool.equals(WorkflowModelProfile.WORK))throw invalid();
        if(args.isEmpty())return work(row);
        if(WorkflowSnapshotWork.supports(store.definition(row).moduleId())&&Boolean.TRUE.equals(args.get("analysis")))return snapshot.page(row.attemptId(),args);
        if(WorkflowHistoryAnalysis.supports(store.definition(row).moduleId())&&Boolean.TRUE.equals(args.get("analysis")))return history.page(row.attemptId(),args);
        if(WorkflowTestReview.supports(store.definition(row).moduleId())&&Boolean.TRUE.equals(args.get("testCases")))return sourceDesign.cases(row,args);
        if(store.definition(row).outputs().stream().noneMatch(output->output.kind()==WorkflowGraph.DataKind.PLAN))throw invalid();
        if(Boolean.TRUE.equals(args.get("catalog")) && Set.of("catalog","query","cursor","limit").containsAll(args.keySet()))
            return presets.list(optionalText(args,"query",200),optionalText(args,"cursor",2048),integer(args,"limit",20,1,100));
        if(args.keySet().equals(Set.of("presetId","presetVersion")))return presets.get(text(args,"presetId",80),integer(args,"presetVersion",-1,1,Integer.MAX_VALUE));
        throw invalid();
    }
    private static String optionalText(Map<String,Object> args,String name,int max) {
        if(!args.containsKey(name))return null;
        if(!(args.get(name) instanceof String value) || value.length()>max)throw invalid();
        return value;
    }
    private Object work(Launch row) {
        var attempt=store.attempt(row); var definition=store.definition(row); var inputs=nodes.inputSnapshot(attempt);
        var values=inputs.values().stream().map(input->Map.of("name",input.name(),"kind",input.kind(),
                "source",input.source(),"sha256",input.sha256())).toList();
        var result=new LinkedHashMap<String,Object>(Map.of("attemptId",attempt.id(),"expectedAttemptVersion",attempt.version(),"title",definition.title(),
                "task",definition.task(),"objective",inputs.objective(),"inputs",values,"outputs",definition.outputs(),
                "outcomes",definition.outcomes(),"completion",definition.completion(),
                "submission",Map.of("tool",WorkflowModelProfile.SUBMIT,"required",List.of("requestKey","expectedAttemptVersion","delivery"),
                        "delivery",Map.of("summary","说明交付的内容","outcome","声明的业务结果；没有声明时省略","outputs",WorkflowModelProfile.writer(attempt.adapterKey())?"按名称填写 {kind,content}；省略所有 CODE 输出，由程序停止后保存代码并填写":"按名称填写 {kind,content}"))));
        result.put("parameters",definition.parameters());
        if(WorkflowSnapshotWork.supports(definition.moduleId()))result.put("snapshotReview",snapshot.work(attempt.id()));
        if(WorkflowHistoryAnalysis.supports(definition.moduleId()))result.put("historyAnalysis",history.work(attempt.id()));
        if(WorkflowKnowledgeBundle.supports(definition.moduleId()))result.put("knowledgeEvidence",WorkflowKnowledgeBundle.submission());
        if(WorkflowDocumentReview.supports(definition.moduleId()))result.put("documentReview",documentReview.work(row));
        if(WorkflowSourceReads.supports(definition.moduleId()))result.put(WorkflowTestReview.supports(definition.moduleId())?"testReview":WorkflowTestWrite.supports(definition.moduleId())?"testWrite":WorkflowTestDesign.supports(definition.moduleId())?"testDesign":"sourceDesign",sourceDesign.work(row));
        if(inputs.values().stream().anyMatch(value->Set.of(WorkflowGraph.DataKind.CODE,WorkflowGraph.DataKind.DOCUMENT).contains(value.kind())))
            result.put("files",Map.of("listTool",WorkflowModelProfile.FILES,"readTool",WorkflowModelProfile.FILE,"instruction",
                    "按固定输入名称分页列文件并读取正文。源码资料会标注 target 和 exclusion；只读上下文不扩大目标范围，排除项没有正文时不可读取。禁止改读当前目录代替冻结资料。"));
        if(WorkflowReviewContract.reviewer(definition.moduleId()))result.put("reviewSubmission",WorkflowReviewContract.submission());
        if(definition.outputs().stream().anyMatch(output->output.kind()==WorkflowGraph.DataKind.PLAN)) {
            result.put("planning",plans.context(attempt));
            result.put("planningPresets",Map.of("tool",WorkflowModelProfile.WORK,"listArgs",Map.of("catalog",true,"limit",20),
                    "readArgs",Map.of("presetId","使用目录中的 id","presetVersion",1),
                    "instruction","先分页查询可用预设，再读取指定版本。复制 node 并设置新 id、任务和显式输入/依赖；保留真实 roleId 与 roleRevisionId，不猜测角色版本。预设查询不授权执行，候选仍需用户确认。"));
        }
        return result;
    }
    private Object input(Launch row, Map<String,Object> args) {
        if (!Set.of("name","offset","limit").containsAll(args.keySet()) || !args.containsKey("name")) throw invalid();
        String name=text(args,"name",64);
        int offset=integer(args,"offset",0,0,2_097_152), limit=integer(args,"limit",12_000,1,12_000);
        var input=nodes.input(store.attempt(row),name);
        String content=WorkflowInputPages.text(input,store.encoding());
        var page=WorkflowInputPages.page(input,content,offset,limit);
        if(WorkflowSourceReads.supports(store.definition(row).moduleId()))sourceDesign.input(row,input,content,offset,offset+page.text().length());
        if(WorkflowDocumentReview.supports(store.definition(row).moduleId()))documentReview.input(row,input,content,offset,offset+page.text().length());
        var result=new LinkedHashMap<String,Object>();
        result.put("name",page.name());result.put("kind",page.kind());result.put("sha256",page.sha256());
        result.put("text",page.text());result.put("offset",page.offset());result.put("nextOffset",page.nextOffset());
        result.put("totalLength",page.totalLength());return result;
    }
}
