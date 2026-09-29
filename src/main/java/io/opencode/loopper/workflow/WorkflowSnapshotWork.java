package io.opencode.loopper.workflow;

import io.opencode.loopper.template.SnapshotReview;
import io.opencode.loopper.template.SnapshotReviewLightweightPolicy;
import java.util.*;

/** Frozen V3 review work; planning and report generation are separate program nodes. */
public final class WorkflowSnapshotWork {
    public static final String ANALYZE="snapshot.analyze", REVIEW="snapshot.review";
    public static final String ANALYSIS_TYPE="SNAPSHOT_ANALYSIS", REVIEW_TYPE="SNAPSHOT_REVIEW";
    public static final int MAX_INPUT_BYTES=2*1024*1024, MAX_DELIVERY_BYTES=1500000;
    private WorkflowSnapshotWork(){ }
    public static boolean supports(String module){return ANALYZE.equals(module)||REVIEW.equals(module);}
    public record Location(String unitId,String path){ }
    public record Reuse(String sourceRequirementId,String sourceAttemptId,String sourceTitle,String sourceNodeTitle,String sourceDeliverySha256){ }
    public record Analysis(int version,String type,WorkflowReviewSource.Reference source,int batchOrdinal,int batchCount,List<Location> locations,SnapshotReview.Analysis claims,
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) Reuse reuse){
        public Analysis(int version,String type,WorkflowReviewSource.Reference source,int batchOrdinal,int batchCount,List<Location> locations,SnapshotReview.Analysis claims){this(version,type,source,batchOrdinal,batchCount,locations,claims,null);}
    }
    public static boolean reuseAllowed(WorkflowGraph.Node node){
        String mode=node.parameters().getOrDefault("snapshotReuse","ALLOW");
        if(!Set.of("ALLOW","BYPASS").contains(mode))throw new IllegalArgumentException("请选择允许历史结果复用或每次重新分析。");
        return WorkflowSnapshotWork.ANALYZE.equals(node.moduleId())&&mode.equals("ALLOW");
    }
    public record Problem(String key,String title){ }
    public record Review(int version,String type,WorkflowReviewSource.Reference source,String analysisAttempt,List<Problem> findings,SnapshotReview.Review claims){ }
    public record Dependency(String attempt,Review result){ }
    public record Input(int version,String module,WorkflowReviewSource.Reference source,String sourceAttempt,int batchOrdinal,int batchCount,
                        String targetSha,String baselineSha,SnapshotReview.Input batch,SnapshotReview.Analysis analysis,List<Dependency> reviews){ }
    public static int ordinal(WorkflowGraph.Node node){
        try{int n=Integer.parseInt(node.parameters().getOrDefault("snapshotBatchOrdinal","0"));if(n<0||n>100000)throw new IllegalArgumentException();return n;}
        catch(RuntimeException invalid){throw new IllegalArgumentException("版本审查批次序号应为 0–100000。");}
    }
    public static void require(WorkflowGraph.Node node){
        boolean analysis=ANALYZE.equals(node.moduleId());if(analysis){ordinal(node);reuseAllowed(node);}
        if(!supports(node.moduleId())||node.moduleVersion()!=1||node.kind()!=WorkflowGraph.NodeKind.WORK||node.completion()==null
            ||node.completion().kind()!=WorkflowGraph.CompletionKind.DELIVERABLES
            ||!(analysis?new HashSet<>(node.outcomes()).equals(Set.of("HAS_FINDINGS","NO_FINDINGS")):node.outcomes().isEmpty())
            ||node.inputs().stream().filter(i->i.name().equals("source")&&i.kind()==WorkflowGraph.DataKind.DOCUMENT&&i.required()&&i.source()==WorkflowGraph.InputSource.NODE).count()!=1
            ||node.inputs().stream().anyMatch(i->!i.name().equals("source")&&i.kind()!=WorkflowGraph.DataKind.TEXT
                &&(analysis||i.kind()!=WorkflowGraph.DataKind.JSON||!i.required()||i.source()!=WorkflowGraph.InputSource.NODE))
            ||!analysis&&node.inputs().stream().noneMatch(i->i.name().equals("analysis")&&i.kind()==WorkflowGraph.DataKind.JSON)
            ||node.outputs().size()!=2||node.outputs().stream().noneMatch(o->o.name().equals("summary")&&o.kind()==WorkflowGraph.DataKind.TEXT&&o.required())
            ||node.outputs().stream().noneMatch(o->o.name().equals("analysis")&&o.kind()==WorkflowGraph.DataKind.JSON&&o.required()))
            throw new IllegalArgumentException("版本审查需要固定资料、分析和说明交付；分析声明有问题/无问题两条结果，复核须绑定已完成分析。");
    }
    public static Input prepare(WorkflowGraph.Node node,WorkflowReviewSource.Reference source,String producer,SnapshotReview.Snapshot snapshot,
                                String analysisAttempt,Analysis analysis,List<Dependency> reviews){
        require(node);var groups=SnapshotReviewLightweightPolicy.plan(snapshot.units()).groups();
        int ordinal=ANALYZE.equals(node.moduleId())?ordinal(node):analysis.batchOrdinal();
        if(ordinal<0||ordinal>=groups.size())throw new IllegalArgumentException("所选版本审查批次不存在；空变化无需模型分析。");
        var batch=SnapshotReviewLightweightPolicy.analysis(snapshot.units(),groups.get(ordinal));
        if(!batch.compact())throw new IllegalArgumentException("新流程需要完整 V3 初始证据，不能混用旧批次。");
        if(REVIEW.equals(node.moduleId())){
            if(analysis.version()!=1||!ANALYSIS_TYPE.equals(analysis.type())||!analysis.source().equals(source)||analysis.batchCount()!=groups.size()
                    ||analysis.claims().findings().isEmpty())throw new IllegalArgumentException("独立复核只处理同一固定资料中已完成分析的候选问题。");
            batch=SnapshotReviewLightweightPolicy.review(analysisAttempt,batch,analysis.claims());
        }
        return new Input(1,node.moduleId(),source,producer,ordinal,groups.size(),snapshot.targetSha(),snapshot.baselineSha(),batch,analysis==null?null:analysis.claims(),List.copyOf(reviews));
    }
}
