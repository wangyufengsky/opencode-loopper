package io.opencode.loopper.service.workflow;

import io.opencode.loopper.domain.LifecycleEvent;
import io.opencode.loopper.workflow.*;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Database-only join: fixed evidence, completion, audit and command receipt commit together. */
@Service
public class WorkflowTestSummaryService {
    private final WorkflowNodeActions actions;
    private final WorkflowNodeRuns nodes;
    private final WorkflowTestSummaryBuilder builder;
    private final WorkflowCommands commands;
    private final WorkflowEncoding encoding;
    private final WorkflowSettlement settlement;
    public WorkflowTestSummaryService(WorkflowNodeActions actions,WorkflowNodeRuns nodes,WorkflowTestSummaryBuilder builder,WorkflowCommands commands,WorkflowEncoding encoding,WorkflowSettlement settlement){this.actions=actions;this.nodes=nodes;this.builder=builder;this.commands=commands;this.encoding=encoding;this.settlement=settlement;}
    @Transactional
    public WorkflowNodeActions.Receipt dispatch(String id,String key,WorkflowNodeActions.Start request,WorkflowDispatch.Permit permit) {
        String digest=encoding.digest("NODE_TEST_SUMMARY",id+"/"+key,request);var replay=commands.replay(request.requestKey(),digest,WorkflowNodeActions.Receipt.class);if(replay.isPresent())return replay.get();
        var admission=actions.admit(id,key,request,permit);var result=builder.build(admission.definition(),admission.inputs());
        var attempt=nodes.begin(admission.node(),admission.owner().headRevision(),admission.inputs(),WorkflowTestSummary.ADAPTER,null);
        attempt=nodes.transition(attempt,WorkflowAttemptState.RUNNING,LifecycleEvent.START);nodes.accept(attempt,result.delivery());nodes.stopTestSummary(attempt);
        boolean success=switch(admission.definition().completion().kind()){case VERIFIED->result.passed();case DELIVERABLES->true;case OUTCOME->Objects.equals(admission.definition().completion().expectedOutcome(),result.delivery().outcome());default->false;};
        nodes.finish(attempt,success?WorkflowAttemptState.SUCCEEDED:WorkflowAttemptState.FAILED);settlement.settle(id,!success,attempt.id());
        return actions.acknowledge(request.requestKey(),digest,"TEST_SUMMARY",id,key,attempt.id());
    }
}
