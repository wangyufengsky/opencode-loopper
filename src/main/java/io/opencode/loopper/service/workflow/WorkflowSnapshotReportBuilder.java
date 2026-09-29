package io.opencode.loopper.service.workflow;

import io.opencode.loopper.service.*;
import io.opencode.loopper.template.*;
import io.opencode.loopper.workflow.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.stereotype.Component;

/** A complete report uses only exact accepted, stopped analysis and the user's explicit review policy. */
@Component
public final class WorkflowSnapshotReportBuilder {
    private final WorkflowSnapshotInputs inputs;
    private final WorkflowSnapshotEvidence evidence;
    private final WorkflowSnapshotReportNames names;
    private final WorkflowEncoding encoding;
    private final SnapshotReviewAuthors authors;
    private final GitReviewJobs jobs;
    public WorkflowSnapshotReportBuilder(WorkflowSnapshotInputs inputs,WorkflowSnapshotEvidence evidence,WorkflowSnapshotReportNames names,WorkflowEncoding encoding,SnapshotReviewAuthors authors,GitReviewJobs jobs){this.inputs=inputs;this.evidence=evidence;this.names=names;this.encoding=encoding;this.authors=authors;this.jobs=jobs;}
    public WorkflowDocumentBuilder.Result build(WorkflowDocumentStore.Context context) {
        try {
            var policy=WorkflowSnapshotReport.require(context.node());var source=inputs.source(context.inputs());var bundle=names.require(context.attempt().id(),source);
            var snapshot=evidence.read(source.manifest());var plan=SnapshotReviewLightweightPolicy.plan(snapshot.units());
            var accepted=accepted(context.inputs(),source,snapshot,plan,policy);
            var selected=WorkflowSnapshotReportNames.names(bundle.projectName(),source.manifest(),bundle.sequence());
            if(!selected.folder().equals(bundle.folderName())||!selected.main().equals(bundle.mainPath()))throw WorkflowCommands.conflict();
            var rendered=SnapshotReviewReportCompiler.compile(bundle.projectName(),selected,WorkflowSnapshotReportNames.start(source.manifest()),WorkflowSnapshotReportNames.end(source.manifest()),
                snapshot,plan,accepted.analyses(),accepted.reviews(),true,policy==WorkflowDocument.ReviewPolicy.REQUIRED,WorkflowSnapshotReuseNotes.render(accepted.reused()),
                refs->authors.renderFrozen(source.source().snapshotId(),jobs.repository(source.source().snapshotId()),source.manifest().projectPrefix(),snapshot,refs));
            var files=new LinkedHashMap<String,String>();long bytes=0;
            for(var file:rendered.documents()) {
                String path=selected.folder()+"/"+file.path();WorkflowDocumentPaths.require(WorkflowSnapshotReport.TYPE,path);
                if(files.putIfAbsent(path,file.markdown())!=null)throw invalid("报告文件重复。");bytes+=file.markdown().getBytes(StandardCharsets.UTF_8).length;
                if(bytes>64L*1024*1024||files.size()>10000)throw new BadRequestException("SOURCE_ARTIFACT_LIMIT","完整版本报告超过容量，已保留原分析和复核，请缩小明确范围。");
            }
            int findings=accepted.analyses().stream().mapToInt(a->a.analysis().findings().size()).sum();
            int supported=(int)accepted.reviews().stream().flatMap(r->r.review().decisions().stream()).filter(d->d.verdict()==SnapshotReview.Verdict.SUPPORTED).count();
            return new WorkflowDocumentBuilder.Result(files,snapshot.units().size(),accepted.analyses().size(),accepted.reviews().size(),0,policy.name(),
                Map.of("batchCount",plan.groups().size(),"excludedCount",source.manifest().excludedCount(),"candidateCount",findings,"supportedCount",supported,
                    "mainPath",selected.folder()+"/"+selected.main(),"folderName",selected.folder(),"targetSha",snapshot.targetSha()));
        }catch(IllegalArgumentException bad){throw invalid(bad.getMessage());}
    }
    private record Accepted(List<SnapshotReviewReportCompiler.AnalysisResult> analyses,List<SnapshotReviewReportCompiler.ReviewResult> reviews,List<WorkflowSnapshotWork.Reuse> reused){ }
    private Accepted accepted(WorkflowDelivery.Inputs snapshot,WorkflowSnapshotInputs.Source source,SnapshotReview.Snapshot fixed,SnapshotReview.Plan plan,WorkflowDocument.ReviewPolicy policy) {
        var analyses=new TreeMap<Integer,SnapshotReviewReportCompiler.AnalysisResult>();var reviews=new ArrayList<SnapshotReviewReportCompiler.ReviewResult>();var seen=new HashSet<String>();var reused=new ArrayList<WorkflowSnapshotWork.Reuse>();
        for(var input:snapshot.values())if(input.kind()==WorkflowGraph.DataKind.JSON) {
            String module=inputs.owner(snapshot.requirementId(),input);if(!seen.add(input.attemptId()))throw invalid("同一分析或复核不能重复绑定。");
            if(module.equals(WorkflowSnapshotWork.ANALYZE)) {
                var value=encoding.decode(encoding.encode(input.content()),WorkflowSnapshotWork.Analysis.class);int i=value.batchOrdinal();
                if(value.version()!=1||!WorkflowSnapshotWork.ANALYSIS_TYPE.equals(value.type())||!source.source().equals(value.source())||value.batchCount()!=plan.groups().size()||i<0||i>=plan.groups().size()||analyses.containsKey(i))throw invalid("分析批次重复或不属于当前完整版本。");
                var batch=SnapshotReviewLightweightPolicy.analysis(fixed.units(),plan.groups().get(i));
                if(!new HashSet<>(value.claims().coverage().stream().map(SnapshotReview.Coverage::unitId).toList()).equals(new HashSet<>(plan.groups().get(i).unitIds())))throw invalid("分析未覆盖完整批次。");
                if(value.reuse()!=null)reused.add(value.reuse());
                analyses.put(i,new SnapshotReviewReportCompiler.AnalysisResult(input.attemptId(),batch,value.claims()));
            }else {
                var value=encoding.decode(encoding.encode(input.content()),WorkflowSnapshotWork.Review.class);
                if(value.version()!=1||!WorkflowSnapshotWork.REVIEW_TYPE.equals(value.type())||!source.source().equals(value.source()))throw invalid("复核不属于当前固定版本。");
                reviews.add(new SnapshotReviewReportCompiler.ReviewResult(input.attemptId(),value.analysisAttempt(),value.claims()));
            }
        }
        if(analyses.size()!=plan.groups().size())throw invalid("完整报告尚缺少分析批次，请绑定全部已完成分析。");
        var byId=new HashMap<String,SnapshotReviewReportCompiler.AnalysisResult>();analyses.values().forEach(a->byId.put(a.id(),a));var reviewed=new HashSet<String>();
        for(var review:reviews){var sourceAnalysis=byId.get(review.analysisId());if(sourceAnalysis==null||sourceAnalysis.analysis().findings().isEmpty()||!reviewed.add(review.analysisId()))throw invalid("复核对应的分析未绑定，或同批复核重复。");}
        if(policy==WorkflowDocument.ReviewPolicy.REQUIRED&&analyses.values().stream().anyMatch(a->!a.analysis().findings().isEmpty()&&!reviewed.contains(a.id())))throw invalid("当前策略要求每个有候选问题的批次完成独立复核。");
        for(var review:reviews)for(var decision:review.review().decisions())if(decision.verdict()==SnapshotReview.Verdict.DUPLICATE&&decision.duplicateOf().contains("/")) {
            String target=decision.duplicateOf();if(reviews.stream().noneMatch(r->r.review().decisions().stream().anyMatch(d->d.verdict()==SnapshotReview.Verdict.SUPPORTED&&target.equals(r.id()+"/"+d.findingKey()))))throw invalid("重复问题的独立支持来源未绑定到本次报告。");
        }
        return new Accepted(List.copyOf(analyses.values()),List.copyOf(reviews),List.copyOf(reused));
    }
    private static BadRequestException invalid(String message){return new BadRequestException("WORKFLOW_SNAPSHOT_REPORT_INVALID",message);}
}
