package io.opencode.loopper.service.workflow;

import io.opencode.loopper.persistence.*;
import io.opencode.loopper.workflow.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Database-only proof of exact accepted sources and explicitly bound review dependencies. */
@Service
@Transactional(readOnly=true)
public class WorkflowSnapshotInputs {
    private final WorkflowReviewRecords sources;
    private final WorkflowPlanMapper plans;
    private final WorkflowExecutionMapper runs;
    private final WorkflowEncoding encoding;
    public WorkflowSnapshotInputs(WorkflowReviewRecords sources,WorkflowPlanMapper plans,WorkflowExecutionMapper runs,WorkflowEncoding encoding){this.sources=sources;this.plans=plans;this.runs=runs;this.encoding=encoding;}
    public record Context(WorkflowReviewSource.Reference source,String producer,WorkflowReviewSource.Manifest manifest,String analysisAttempt,WorkflowSnapshotWork.Analysis analysis,List<WorkflowSnapshotWork.Dependency> reviews){ }
    public record Source(WorkflowReviewSource.Reference source,String producer,WorkflowReviewSource.Manifest manifest){ }
    public Source source(WorkflowDelivery.Inputs snapshot){
        var input=snapshot.values().stream().filter(i->i.name().equals("source")).findFirst().orElseThrow(WorkflowCommands::conflict);
        if(input.kind()!=WorkflowGraph.DataKind.DOCUMENT||!"NODE".equals(input.source())||input.attemptId()==null)throw WorkflowCommands.conflict();
        var reference=encoding.decode(encoding.encode(input.content()),WorkflowReviewSource.Reference.class);
        var project=plans.find(snapshot.requirementId()).orElseThrow(WorkflowCommands::conflict).projectId();
        var manifest=sources.manifest(project,snapshot.requirementId(),input.attemptId(),reference);
        return new Source(reference,input.attemptId(),manifest);
    }
    public Context read(WorkflowGraph.Node node,WorkflowDelivery.Inputs snapshot){
        WorkflowSnapshotWork.require(node);var fixed=source(snapshot);var reference=fixed.source();
        String producer=null;WorkflowSnapshotWork.Analysis analysis=null;var reviews=new ArrayList<WorkflowSnapshotWork.Dependency>();var seen=new HashSet<String>();
        for(var value:snapshot.values())if(value.kind()==WorkflowGraph.DataKind.JSON){
            String module=owner(snapshot.requirementId(),value);if(!seen.add(value.attemptId()))throw WorkflowCommands.conflict();
            if(value.name().equals("analysis")){
                analysis=encoding.decode(encoding.encode(value.content()),WorkflowSnapshotWork.Analysis.class);producer=value.attemptId();
                if(!module.equals(WorkflowSnapshotWork.ANALYZE)||analysis.version()!=1||!WorkflowSnapshotWork.ANALYSIS_TYPE.equals(analysis.type())||!reference.equals(analysis.source()))throw WorkflowCommands.conflict();
            }else{
                var review=encoding.decode(encoding.encode(value.content()),WorkflowSnapshotWork.Review.class);
                if(!module.equals(WorkflowSnapshotWork.REVIEW)||review.version()!=1||!WorkflowSnapshotWork.REVIEW_TYPE.equals(review.type())||!reference.equals(review.source()))throw WorkflowCommands.conflict();
                reviews.add(new WorkflowSnapshotWork.Dependency(value.attemptId(),review));
            }
        }
        if(WorkflowSnapshotWork.REVIEW.equals(node.moduleId())&&(analysis==null||analysis.claims().findings().isEmpty()))
            throw new IllegalArgumentException("复核须绑定含候选问题的已完成分析；无问题批次无需创建复核会话。");
        return new Context(reference,fixed.producer(),fixed.manifest(),producer,analysis,List.copyOf(reviews));
    }
    public String owner(String requirement,WorkflowDelivery.Input input){
        if(!"NODE".equals(input.source())||input.attemptId()==null||!"analysis".equals(input.outputName()))throw WorkflowCommands.conflict();
        var attempt=runs.attempt(input.attemptId()).orElseThrow(WorkflowCommands::conflict);var node=runs.node(attempt.nodeRunId()).orElseThrow(WorkflowCommands::conflict);
        var definition=encoding.decode(node.definitionJson(),WorkflowGraph.Node.class);
        if(!node.requirementId().equals(requirement)||!attempt.state().equals("SUCCEEDED")||runs.stop(attempt.id()).isEmpty()||!WorkflowSnapshotWork.supports(definition.moduleId())||definition.moduleVersion()!=1)throw WorkflowCommands.conflict();
        var delivery=runs.delivery(attempt.id()).orElseThrow(WorkflowCommands::conflict);var value=encoding.decode(delivery.contentJson(),WorkflowDelivery.class).outputs().get("analysis");
        if(!WorkflowEncoding.hash(delivery.contentJson()).equals(delivery.sha256())||!delivery.sha256().equals(input.sha256())||value==null||!encoding.encode(value.content()).equals(encoding.encode(input.content())))throw WorkflowCommands.conflict();
        return definition.moduleId();
    }
}
