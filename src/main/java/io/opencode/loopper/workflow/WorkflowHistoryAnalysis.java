package io.opencode.loopper.workflow;

import io.opencode.loopper.template.*;
import java.util.*;

/** History analysis reuses the frozen Git partition, source-address and contribution policies. */
public final class WorkflowHistoryAnalysis {
    public static final String REVIEW="history.review", CONTRIBUTION="history.contribution";
    public static final String REVIEW_TYPE="HISTORY_REVIEW", CONTRIBUTION_TYPE="HISTORY_CONTRIBUTION";
    public static final int MAX_INPUT_BYTES=2*1024*1024;
    private WorkflowHistoryAnalysis(){ }
    public static boolean supports(String module){return REVIEW.equals(module)||CONTRIBUTION.equals(module);}
    public record Review(int version,String type,WorkflowHistorySnapshot.Reference source,int batchOrdinal,int batchCount,
                         List<TemplateAnalysis.UnitReview> reviews,List<Location> locations){ }
    public record Location(String unitId,String commitSha,String path){ }
    public static List<Location> locations(Input input){return input.units().stream().map(u->new Location(u.id(),u.commitSha(),u.path())).toList();}
    public record Contribution(int version,String type,WorkflowHistorySnapshot.Reference source,TemplateContributionFacts.Person person,
                               List<String> reviewAttempts,TemplateAnalysis.ContributorCandidate assessment){ }
    public record Input(int version,String module,WorkflowHistorySnapshot.Reference source,String sourceAttempt,int batchOrdinal,int batchCount,
                        List<TemplateAnalysis.Unit> units,TemplateContributionFacts.Person person,List<TemplateAnalysis.UnitReview> reviews,
                        List<String> reviewAttempts){ }
    public static int ordinal(WorkflowGraph.Node node) {
        try{int value=Integer.parseInt(node.parameters().getOrDefault("historyBatchOrdinal","0"));if(value<0||value>100000)throw new IllegalArgumentException();return value;}
        catch(RuntimeException invalid){throw new IllegalArgumentException("历史审查批次序号应为 0–100000。");}
    }
    public static String email(WorkflowGraph.Node node) {
        String value=node.parameters().getOrDefault("historyContributorEmail","");
        if(value.isBlank()||value.length()>2000)throw new IllegalArgumentException("请填写固定历史中的贡献者邮箱。");return value.strip().toLowerCase(Locale.ROOT);
    }
    public static void require(WorkflowGraph.Node node) {
        boolean review=REVIEW.equals(node.moduleId());if(review)ordinal(node);else email(node);
        if(!supports(node.moduleId())||node.moduleVersion()!=1||node.kind()!=WorkflowGraph.NodeKind.WORK||node.completion()==null
            ||node.completion().kind()!=WorkflowGraph.CompletionKind.DELIVERABLES||!node.outcomes().isEmpty()
            ||node.inputs().stream().filter(i->i.name().equals("source")&&i.kind()==WorkflowGraph.DataKind.DOCUMENT&&i.required()&&i.source()==WorkflowGraph.InputSource.NODE).count()!=1
            ||node.inputs().stream().anyMatch(i->!i.name().equals("source")&&i.kind()!=WorkflowGraph.DataKind.TEXT
                &&(review||i.kind()!=WorkflowGraph.DataKind.JSON||!i.required()||i.source()!=WorkflowGraph.InputSource.NODE))
            ||!review&&node.inputs().stream().noneMatch(i->i.kind()==WorkflowGraph.DataKind.JSON)
            ||node.outputs().size()!=2||node.outputs().stream().noneMatch(o->o.name().equals("summary")&&o.kind()==WorkflowGraph.DataKind.TEXT&&o.required())
            ||node.outputs().stream().noneMatch(o->o.name().equals("analysis")&&o.kind()==WorkflowGraph.DataKind.JSON&&o.required()))
            throw new IllegalArgumentException("历史分析需要固定历史资料和分析、说明交付；贡献评价还需要覆盖本人变更的已完成审查。");
    }
    public static Input prepare(WorkflowGraph.Node node,WorkflowHistorySnapshot.Reference source,String producer,TemplateGitEvidence evidence,
                                List<Review> reviews,List<String> reviewAttempts) {
        require(node);var units=TemplateAnalysisPartitioner.units(evidence);var batches=TemplateAnalysisPartitioner.batches(units);
        if(REVIEW.equals(node.moduleId())) {
            int ordinal=ordinal(node);if(ordinal>=batches.size())throw new IllegalArgumentException("所选历史审查批次不存在；空范围无需模型分析。");
            return new Input(1,REVIEW,source,producer,ordinal,batches.size(),batches.get(ordinal),null,List.of(),List.of());
        }
        var person=TemplateContributionFacts.people(evidence).stream().filter(p->p.author().email().equals(email(node))).findFirst()
                .orElseThrow(()->new IllegalArgumentException("固定历史中不存在该贡献者。"));
        var accepted=new LinkedHashMap<String,TemplateAnalysis.UnitReview>();
        for(var review:reviews) {
            if(review.version()!=1||!REVIEW_TYPE.equals(review.type())||!source.equals(review.source())||review.batchCount()!=batches.size()
                    ||review.batchOrdinal()<0||review.batchOrdinal()>=batches.size())throw new IllegalArgumentException("审查结果与固定历史批次不一致。");
            for(var item:TemplateAnalysisValidation.batch(batches.get(review.batchOrdinal()),new TemplateAnalysis.BatchCandidate(review.reviews())))
                if(accepted.putIfAbsent(item.unitId(),item)!=null)throw new IllegalArgumentException("重复绑定了同一历史证据的审查结果。");
        }
        var owned=units.stream().filter(u->person.evidenceIds().contains(u.evidenceId())).toList();
        if(owned.stream().anyMatch(u->!accepted.containsKey(u.id())))throw new IllegalArgumentException("贡献评价前须绑定覆盖本人全部变更的已完成审查。");
        return new Input(1,CONTRIBUTION,source,producer,0,batches.size(),owned,person,
                owned.stream().map(u->accepted.get(u.id())).toList(),List.copyOf(reviewAttempts));
    }
}
