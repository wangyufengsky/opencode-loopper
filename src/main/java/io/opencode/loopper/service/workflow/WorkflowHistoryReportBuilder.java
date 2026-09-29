package io.opencode.loopper.service.workflow;

import io.opencode.loopper.service.BadRequestException;
import io.opencode.loopper.template.*;
import io.opencode.loopper.workflow.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.stereotype.Component;

/** Compile only complete accepted history analysis, with the original deterministic layouts and scoring formula. */
@Component
public final class WorkflowHistoryReportBuilder {
    private final WorkflowHistoryInputs inputs;
    private final WorkflowHistoryEvidence evidence;
    private final WorkflowHistoryReportFormats formats;
    public WorkflowHistoryReportBuilder(WorkflowHistoryInputs inputs,WorkflowHistoryEvidence evidence,WorkflowHistoryReportFormats formats){this.inputs=inputs;this.evidence=evidence;this.formats=formats;}
    public WorkflowDocumentBuilder.Result build(WorkflowDocumentStore.Context context) {
        try {
            WorkflowHistoryReport.require(context.node());var kind=WorkflowHistoryReport.kind(context.node());
            var source=inputs.read(context.inputs());var fixed=formats.require(context.attempt(),source);var history=evidence.read(source.manifest());
            var units=TemplateAnalysisPartitioner.units(history);var batches=TemplateAnalysisPartitioner.batches(units);
            var reviews=reviews(source,batches);var contributions=contributions(source,history,batches,kind);
            var rendered=TemplateReportCompiler.compile(kind,fixed.bundle().projectName(),history,new TemplateAnalysis.Accepted(reviews,contributions),fixed.layout(),fixed.bundle().sequence());
            var files=new LinkedHashMap<String,String>();long bytes=0;
            for(var document:rendered.documents()) {
                String path=fixed.bundle().folderName()+"/"+document.path();WorkflowDocumentPaths.require(WorkflowHistoryReport.TYPE,path);
                if(files.putIfAbsent(path,document.markdown())!=null)throw new IllegalArgumentException("报告文件名重复。");
                bytes+=document.markdown().getBytes(StandardCharsets.UTF_8).length;
                if(files.size()>10000||bytes>64L*1024*1024)throw new BadRequestException("SOURCE_ARTIFACT_LIMIT","完整历史报告超出存储上限，请缩小明确日期范围；原分析结果已保留。");
            }
            String main=fixed.bundle().folderName()+"/"+fixed.bundle().mainPath();if(!files.containsKey(main))throw WorkflowCommands.conflict();
            return new WorkflowDocumentBuilder.Result(files,history.commits().size(),source.reviews().size(),0,0,"NONE",
                Map.of("reportKind",kind.name(),"unitCount",units.size(),"batchCount",batches.size(),"contributorCount",TemplateContributionFacts.people(history).size(),
                    "assessedContributorCount",contributions.size(),"mainPath",main,"folderName",fixed.bundle().folderName(),"layoutVersion",fixed.layout().version(),"layoutSha256",fixed.layout().sha256()));
        }catch(IllegalArgumentException bad){throw invalid(bad.getMessage());}
    }
    private List<TemplateAnalysis.UnitReview> reviews(WorkflowHistoryInputs.Context source,List<List<TemplateAnalysis.Unit>> batches) {
        var seen=new HashSet<Integer>();var result=new ArrayList<TemplateAnalysis.UnitReview>();
        for(var item:source.reviews()) {
            var value=item.value();int index=value.batchOrdinal();
            if(index<0||index>=batches.size()||value.batchCount()!=batches.size()||!seen.add(index))throw invalid("历史审查批次重复或不属于完整固定范围。");
            result.addAll(TemplateAnalysisValidation.batch(batches.get(index),new TemplateAnalysis.BatchCandidate(value.reviews())));
        }
        if(seen.size()!=batches.size())throw invalid("历史报告尚缺少审查批次，请绑定全部已完成批次。");return List.copyOf(result);
    }
    private List<TemplateAnalysis.ContributorCandidate> contributions(WorkflowHistoryInputs.Context source,TemplateGitEvidence history,List<List<TemplateAnalysis.Unit>> batches,TemplateTaskDefinition kind) {
        if(kind==TemplateTaskDefinition.CODE_REVIEW&&!source.contributions().isEmpty())throw invalid("历史提交审查不包含个人评分，请选择相应报告类型。");
        var people=new HashMap<String,TemplateContributionFacts.Person>();TemplateContributionFacts.people(history).forEach(p->people.put(p.author().identity(),p));
        var reviews=new HashMap<String,Integer>();source.reviews().forEach(r->reviews.put(r.attempt(),r.value().batchOrdinal()));
        var result=new ArrayList<TemplateAnalysis.ContributorCandidate>();var identities=new HashSet<String>();
        for(var item:source.contributions()) {
            var value=item.value();var person=people.get(value.assessment().identity());
            if(person==null||!person.equals(value.person())||!identities.add(person.author().identity()))throw invalid("个人评价身份重复或与固定历史不一致。");
            var referenced=new HashSet<>(value.reviewAttempts());
            if(referenced.size()!=value.reviewAttempts().size()||!reviews.keySet().containsAll(referenced))throw invalid("个人评价引用了本次报告未绑定的审查版本。");
            for(var review:source.reviews())if(batches.get(review.value().batchOrdinal()).stream().anyMatch(u->person.evidenceIds().contains(u.evidenceId()))&&!referenced.contains(review.attempt()))
                throw invalid("个人评价未覆盖报告中属于本人的完整审查。");
            result.add(TemplateAnalysisValidation.contributor(person.author().identity(),person.evidenceIds(),value.assessment()));
        }
        if(kind==TemplateTaskDefinition.CONTRIBUTION_REPORT&&people.values().stream().anyMatch(p->!p.author().robot()&&p.effectiveLines()>0&&!identities.contains(p.author().identity())))
            throw invalid("贡献周报尚缺少人员评价，请绑定全部适用人员的已完成评价。");
        return List.copyOf(result);
    }
    private static BadRequestException invalid(String message){return new BadRequestException("WORKFLOW_HISTORY_REPORT_INVALID",message);}
}
