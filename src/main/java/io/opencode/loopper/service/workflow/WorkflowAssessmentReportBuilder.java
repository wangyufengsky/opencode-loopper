package io.opencode.loopper.service.workflow;

import io.opencode.loopper.service.*;
import io.opencode.loopper.template.*;
import io.opencode.loopper.workflow.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.stereotype.Component;

/** Full fixed original coverage and exact independent opinions precede report generation. */
@Component
public final class WorkflowAssessmentReportBuilder {
    private final WorkflowDocumentReviewContext contexts;
    private final WorkflowDocumentReviewContract reviews;
    private final WorkflowNodeRuns nodes;
    private final WorkflowEncoding encoding;
    public WorkflowAssessmentReportBuilder(WorkflowDocumentReviewContext contexts,WorkflowDocumentReviewContract reviews,WorkflowNodeRuns nodes,WorkflowEncoding encoding){this.contexts=contexts;this.reviews=reviews;this.nodes=nodes;this.encoding=encoding;}
    private record Opinion(String author,boolean crossBatchComplete){ }
    public WorkflowDocumentBuilder.Result build(WorkflowDocumentStore.Context context) {
        var policy=WorkflowDocument.require(context.node());var fixed=contexts.all(context.inputs());
        var drafts=new LinkedHashMap<String,WorkflowDelivery.Input>();var candidates=new LinkedHashMap<String,DirectDocumentAssessment.Candidate>();
        var assigned=new HashSet<DirectDocumentAssessment.Source>();var ordinals=new HashSet<Integer>();
        for(var input:context.inputs().values())if(input.kind()==WorkflowGraph.DataKind.JSON) {
            var producer=reviews.draft(fixed,input,false);
            if(drafts.putIfAbsent(input.attemptId(),input)!=null||!ordinals.add(producer.ordinal()))throw invalid("本次汇总包含重复评审稿或重复批次编号。");
            for(var section:producer.assigned())if(!assigned.add(section.source()))throw invalid("原文章节被重复分配，请检查批次范围。");
            candidates.put(input.attemptId(),encoding.decode(encoding.encode(input.content()),DirectDocumentAssessment.Candidate.class));
        }
        if(!assigned.equals(new HashSet<>(fixed.assigned().stream().map(WorkflowDocumentReviewContext.Section::source).toList())))throw invalid("汇总尚未覆盖全部固定原文章节，请补齐缺失批次。");
        var opinions=new LinkedHashMap<String,DirectDocumentAssessment.Review>();var crossBatch=new HashSet<String>();
        for(var input:context.inputs().values())if(input.kind()==WorkflowGraph.DataKind.DECISION) {
            var opinion=opinion(fixed,input,drafts);String author=opinion.author();var review=encoding.decode(encoding.encode(input.content()),DirectDocumentAssessment.Review.class);
            if(opinions.putIfAbsent(author,review)!=null)throw invalid("同一评审稿不能绑定多份复核意见。");
            if(opinion.crossBatchComplete()&&review.approved())crossBatch.add(author);
        }
        int approved=(int)opinions.values().stream().filter(DirectDocumentAssessment.Review::approved).count();int revise=opinions.size()-approved;
        if(policy==WorkflowDocument.ReviewPolicy.REQUIRED&&(approved!=drafts.size()||crossBatch.size()!=drafts.size()))throw new BadRequestException("WORKFLOW_ASSESSMENT_REVIEW_INCOMPLETE","当前策略要求全部评审稿独立复核通过并完成跨批次核对，请补齐复核或明确调整策略。");
        return render(context.node().title(),fixed,candidates,opinions,policy,approved,revise,crossBatch.size());
    }
    private Opinion opinion(WorkflowDocumentReviewContext.Context fixed,WorkflowDelivery.Input input,Map<String,WorkflowDelivery.Input> drafts) {
        if(!"NODE".equals(input.source())||!"review".equals(input.outputName())||input.attemptId()==null)throw invalid("请选择本需求已完成的专业复核意见。");
        var attempt=nodes.attempt(input.attemptId());var owner=nodes.requireNode(attempt.nodeRunId());var definition=nodes.definition(owner);
        if(!owner.requirementId().equals(fixed.requirement())||!attempt.state().equals("SUCCEEDED")||!nodes.hasStop(attempt.id())
            ||!io.opencode.loopper.runtime.WorkflowModelProfile.ADAPTER.equals(attempt.adapterKey())||!WorkflowDocumentReview.REVIEW.equals(definition.moduleId()))throw invalid("复核意见未成功收尾或不属于本需求。");
        var delivery=nodes.delivery(attempt.id());var output=encoding.decode(delivery.contentJson(),WorkflowDelivery.class).outputs().get("review");
        if(!delivery.sha256().equals(input.sha256())||!WorkflowEncoding.hash(delivery.contentJson()).equals(delivery.sha256())||output==null||output.kind()!=WorkflowGraph.DataKind.DECISION||!output.content().equals(input.content()))throw WorkflowCommands.conflict();
        var frozen=nodes.inputs(attempt);var source=reviews.context(definition,frozen);
        if(!fixed.documents().equals(source.documents())||!fixed.code().equals(source.code())||!fixed.codeProducer().equals(source.codeProducer()))throw invalid("复核与汇总不是同一份原文和固定代码。");
        var seen=new HashSet<String>();String primary=null;
        for(var bound:frozen.values())if(bound.kind()==WorkflowGraph.DataKind.JSON) {
            var selected=drafts.get(bound.attemptId());
            if(selected==null||!Objects.equals(selected.sha256(),bound.sha256())||!selected.content().equals(bound.content())||!seen.add(bound.attemptId()))throw invalid("复核稿件与本次汇总不一致。");
            if(bound.name().equals("draft"))primary=bound.attemptId();
        }
        if(primary==null)throw invalid("复核未绑定本批主稿。");
        return new Opinion(primary,seen.equals(drafts.keySet()));
    }
    private WorkflowDocumentBuilder.Result render(String title,WorkflowDocumentReviewContext.Context fixed,Map<String,DirectDocumentAssessment.Candidate> candidates,
            Map<String,DirectDocumentAssessment.Review> opinions,WorkflowDocument.ReviewPolicy policy,int approved,int revise,int crossBatch) {
        var rows=new ArrayList<RequirementReportCompiler.Row>();var keys=new HashSet<String>();var merged=new LinkedHashMap<String,RequirementCodeAssessment.Finding>();
        var limitations=new ArrayList<String>();var coverage=new ArrayList<Map<String,Object>>();var original=new HashMap<DirectDocumentAssessment.Source,String>();
        for(var file:fixed.documentManifest().originals())for(var limitation:file.limitations())limitations.add(file.filename()+"："+limitation);
        for(var candidate:candidates.values()) {
            if(!fixed.codeManifest().commitSha().equals(candidate.snapshotSha()))throw invalid("评审稿未绑定本次固定提交。");
            limitations.addAll(candidate.limitations());candidate.findings().forEach(f->merge(merged,f));
            for(var entry:candidate.entries()) {
                if(!keys.add(entry.assessment().requirementKey()))throw invalid("不同批次存在重复需求编号。");
                var sources=entry.sources().stream().map(ref->new DocumentRequirements.Source(ref.fileId(),ref.section(),original.computeIfAbsent(ref,r->{
                    String text=contexts.original(fixed,contexts.section(fixed,r)).content();return text.length()<=2048?text:text.substring(0,2048)+"\n（原文节选；完整章节见本次固定原文输入。）";
                }))).toList();
                rows.add(new RequirementReportCompiler.Row(new DocumentRequirements.Requirement(entry.assessment().requirementKey(),entry.title(),"原文评审",DocumentRequirements.Kind.FUNCTION,entry.statement(),sources,List.of(),entry.issues()),entry.assessment()));
                for(var ref:entry.sources())coverage.add(Map.of("source",ref,"requirementKey",entry.assessment().requirementKey()));
            }
            for(var skipped:candidate.skippedSections())coverage.add(Map.of("source",skipped.source(),"skippedReason",skipped.reason()));
        }
        var findings=List.copyOf(merged.values());
        if(rows.size()+findings.size()>1023)throw limit();
        if(crossBatch<approved)limitations.add("部分复核未读取全部批次稿件，跨批次核对尚未完整。");
        String status="已完成静态分析；独立复核通过 "+approved+" 批，要求修正 "+revise+" 批，未复核 "+(candidates.size()-opinions.size())+" 批。";
        var rendered=RequirementReportCompiler.review(title,fixed.codeManifest().commitSha(),rows,findings,limitations,status,true);
        var files=new LinkedHashMap<String,String>();rendered.files().forEach(file->files.put(file.name(),file.content()));
        var matrix=new LinkedHashMap<String,Object>();matrix.put("version",1);matrix.put("type",WorkflowDocument.ASSESSMENT_TYPE);matrix.put("snapshotSha",fixed.codeManifest().commitSha());
        matrix.put("documents",fixed.documents());matrix.put("code",fixed.code());matrix.put("sourceCoverageComplete",true);matrix.put("sections",fixed.assigned());
        matrix.put("coverage",coverage);matrix.put("requirements",rows);matrix.put("findings",findings);matrix.put("limitations",limitations.stream().distinct().toList());
        matrix.put("testExecution","NOT_RUN_STATIC_REVIEW");matrix.put("allRequirementsSatisfied",rendered.allRequirementsSatisfied());matrix.put("conclusions",rendered.conclusions());
        matrix.put("reviewPolicy",policy.name());matrix.put("reviews",opinions.values());matrix.put("crossBatchReviewedCount",crossBatch);matrix.put("independentReviewComplete",approved==candidates.size()&&crossBatch==candidates.size());files.put("matrix.json",encoding.encode(matrix));
        if(files.values().stream().mapToLong(text->text.getBytes(StandardCharsets.UTF_8).length).sum()>64L*1024*1024)throw limit();
        return new WorkflowDocumentBuilder.Result(files,fixed.assigned().size(),candidates.size(),approved,revise,policy.name(),Map.of("crossBatchReviewedCount",crossBatch,"requirementCount",rows.size(),"findingCount",findings.size(),"allRequirementsSatisfied",rendered.allRequirementsSatisfied(),"testExecution","NOT_RUN_STATIC_REVIEW"));
    }
    private void merge(Map<String,RequirementCodeAssessment.Finding> findings,RequirementCodeAssessment.Finding finding) {
        String identity=encoding.digest("DOCUMENT_FINDING","",List.of(finding.kind(),finding.title(),finding.trigger(),finding.impact(),finding.evidence().stream().map(r->r.path()+":"+r.blobSha()+":"+r.startLine()).sorted().toList()));
        var previous=findings.get(identity);if(previous==null){findings.put(identity,finding);return;}
        var refs=new TreeSet<>(previous.requirementKeys());refs.addAll(finding.requirementKeys());var severity=previous.severity().ordinal()<finding.severity().ordinal()?previous.severity():finding.severity();
        findings.put(identity,new RequirementCodeAssessment.Finding(previous.key(),previous.kind(),severity,previous.title(),previous.trigger(),previous.impact(),previous.recommendation(),List.copyOf(refs),previous.evidence(),previous.rootCauseKey()));
    }
    private static BadRequestException invalid(String message){return new BadRequestException("WORKFLOW_ASSESSMENT_REPORT_INVALID",message);}
    private static BadRequestException limit(){return new BadRequestException("WORKFLOW_ASSESSMENT_REPORT_LIMIT","完整报告超过存储上限，请按明确业务范围拆分需求；评审稿已保留，未发布截断报告。");}
}
