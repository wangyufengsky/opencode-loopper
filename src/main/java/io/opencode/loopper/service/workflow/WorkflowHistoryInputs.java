package io.opencode.loopper.service.workflow;

import io.opencode.loopper.persistence.*;
import io.opencode.loopper.workflow.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static io.opencode.loopper.service.workflow.WorkflowHistoryAnalysisStore.invalid;

/** Exact accepted history and analysis identities are shared by model preparation, planning and report compilation. */
@Service
@Transactional(readOnly=true)
public class WorkflowHistoryInputs {
    private final WorkflowHistoryRecords history;
    private final WorkflowPlanMapper plans;
    private final WorkflowExecutionMapper runs;
    private final WorkflowEncoding encoding;
    public WorkflowHistoryInputs(WorkflowHistoryRecords history,WorkflowPlanMapper plans,WorkflowExecutionMapper runs,WorkflowEncoding encoding){this.history=history;this.plans=plans;this.runs=runs;this.encoding=encoding;}
    public record Review(String attempt,WorkflowHistoryAnalysis.Review value){ }
    public record Contribution(String attempt,WorkflowHistoryAnalysis.Contribution value){ }
    public record Context(WorkflowHistorySnapshot.Reference source,String producer,WorkflowHistorySnapshot.Manifest manifest,List<Review> reviews,List<Contribution> contributions){ }
    public Context read(WorkflowDelivery.Inputs snapshot) {
        var source=snapshot.values().stream().filter(i->i.name().equals("source")).findFirst().orElseThrow(()->invalid("缺少固定历史资料。"));
        if(source.kind()!=WorkflowGraph.DataKind.DOCUMENT||!"NODE".equals(source.source())||source.attemptId()==null)throw invalid("历史资料必须来自已完成采集节点。");
        var reference=encoding.decode(encoding.encode(source.content()),WorkflowHistorySnapshot.Reference.class);
        var project=plans.find(snapshot.requirementId()).orElseThrow(WorkflowCommands::conflict).projectId();
        var manifest=history.manifest(project,snapshot.requirementId(),source.attemptId(),reference);
        var reviews=new ArrayList<Review>();var contributions=new ArrayList<Contribution>();var attempts=new HashSet<String>();
        for(var input:snapshot.values())if(input.kind()==WorkflowGraph.DataKind.JSON) {
            String module=owner(snapshot.requirementId(),input);
            if(!attempts.add(input.attemptId()))throw invalid("同一份历史分析不能重复绑定。");
            if(WorkflowHistoryAnalysis.REVIEW.equals(module)) {
                var value=encoding.decode(encoding.encode(input.content()),WorkflowHistoryAnalysis.Review.class);
                if(value.version()!=1||!WorkflowHistoryAnalysis.REVIEW_TYPE.equals(value.type())||!reference.equals(value.source()))throw invalid("审查与本节点的历史资料不一致。");
                reviews.add(new Review(input.attemptId(),value));
            } else {
                var value=encoding.decode(encoding.encode(input.content()),WorkflowHistoryAnalysis.Contribution.class);
                if(value.version()!=1||!WorkflowHistoryAnalysis.CONTRIBUTION_TYPE.equals(value.type())||!reference.equals(value.source()))throw invalid("贡献评价与本节点的历史资料不一致。");
                contributions.add(new Contribution(input.attemptId(),value));
            }
        }
        return new Context(reference,source.attemptId(),manifest,List.copyOf(reviews),List.copyOf(contributions));
    }
    private String owner(String requirement,WorkflowDelivery.Input input) {
        if(!"NODE".equals(input.source())||input.attemptId()==null||!"analysis".equals(input.outputName()))throw invalid("历史分析依据必须绑定专业历史分析节点的输出。");
        var attempt=runs.attempt(input.attemptId()).orElseThrow(WorkflowCommands::conflict);
        var node=runs.node(attempt.nodeRunId()).orElseThrow(WorkflowCommands::conflict);
        var definition=encoding.decode(node.definitionJson(),WorkflowGraph.Node.class);
        if(!node.requirementId().equals(requirement)||!attempt.state().equals("SUCCEEDED")||runs.stop(attempt.id()).isEmpty()
                ||!WorkflowHistoryAnalysis.supports(definition.moduleId())||definition.moduleVersion()!=1)throw invalid("所绑定历史分析尚未成功并停止。");
        var delivery=runs.delivery(attempt.id()).orElseThrow(WorkflowCommands::conflict);
        var output=encoding.decode(delivery.contentJson(),WorkflowDelivery.class).outputs().get("analysis");
        if(!WorkflowEncoding.hash(delivery.contentJson()).equals(delivery.sha256())||!delivery.sha256().equals(input.sha256())||output==null
                ||!encoding.encode(output.content()).equals(encoding.encode(input.content())))throw WorkflowCommands.conflict();
        return definition.moduleId();
    }
}
