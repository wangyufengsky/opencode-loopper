package io.opencode.loopper.service.workflow;

import io.opencode.loopper.runtime.DurableCommandProtocol.Result;
import io.opencode.loopper.workflow.*;
import java.util.*;
import org.springframework.stereotype.Component;

/** Strict frozen configuration and bounded public reports; execution identities remain in the run ledger. */
@Component
public final class WorkflowCommandContract {
    private final WorkflowEncoding encoding;
    private final WorkflowNativeTestContract nativeTests;
    private final WorkflowRepositoryContract repositories;
    private final WorkflowHistoryContract histories;
    private final WorkflowReviewSourceContract reviews;
    public WorkflowCommandContract(WorkflowEncoding encoding,WorkflowNativeTestContract nativeTests,WorkflowRepositoryContract repositories,WorkflowHistoryContract histories,WorkflowReviewSourceContract reviews){this.encoding=encoding;this.nativeTests=nativeTests;this.repositories=repositories;this.histories=histories;this.reviews=reviews;}
    public WorkflowNativeTestContract.Context nativeContext(WorkflowGraph.Node node,WorkflowDelivery.Inputs inputs){return WorkflowNativeTest.MODULE.equals(node.moduleId())?nativeTests.resolve(node,inputs):null;}
    public void resolve(WorkflowGraph.Node node,WorkflowDelivery.Inputs inputs){if(reviews.supports(node)){reviews.validate(node,inputs);return;}if(histories.supports(node)){histories.validate(node,inputs);return;}if(repositories.supports(node)){repositories.validate(node,inputs);return;}var context=nativeContext(node,inputs);if(context==null)parse(node);}
    public void admitRepository(io.opencode.loopper.persistence.WorkflowExecutionRows.Attempt attempt,WorkflowNodeActions.Admission admission){repositories.admit(attempt,admission);histories.admit(attempt,admission);reviews.admit(attempt,admission);}
    public WorkflowRepositoryContract.Context repositoryContext(io.opencode.loopper.persistence.WorkflowExecutionRows.Attempt attempt,WorkflowGraph.Node node,WorkflowDelivery.Inputs inputs){return repositories.context(attempt,node,inputs);}
    public WorkflowHistoryContract.Context historyContext(io.opencode.loopper.persistence.WorkflowExecutionRows.Attempt attempt,WorkflowGraph.Node node,WorkflowDelivery.Inputs inputs){return histories.context(attempt,node,inputs);}
    public WorkflowReviewSourceContract.Context reviewContext(io.opencode.loopper.persistence.WorkflowExecutionRows.Attempt attempt,WorkflowGraph.Node node,WorkflowDelivery.Inputs inputs){return reviews.context(attempt,node,inputs);}
    public Evaluation evaluate(WorkflowCommandStore.Context context,Result result,io.opencode.loopper.service.GitSnapshotJobProtocol.Snapshot snapshot,WorkflowHistorySnapshot.Manifest history,WorkflowReviewSource.Manifest review){
        if(context.review()!=null)return reviews.evaluate(context,result,review);
        if(context.history()!=null)return histories.evaluate(context,result,history);
        if(context.repository()!=null)return repositories.evaluate(context,result,snapshot);
        return context.nativeTest()==null?evaluate(context.node(),context.spec(),context.input(),result):nativeTests.evaluate(context.attempt().id(),context.node(),context.nativeTest(),result);
    }
    public WorkflowCommandVerification parse(WorkflowGraph.Node node) { return encoding.command(node); }
    public record Evaluation(WorkflowDelivery delivery,boolean success) { }
    public Evaluation evaluate(WorkflowGraph.Node node,WorkflowCommandVerification spec,WorkflowDelivery.Input input,Result result) {
        boolean valid=result.launched() && result.stopConfirmed() && !result.cancelled() && !result.timedOut() && !result.outputTruncated()
                && result.error().isEmpty() && result.exitCode()!=null;
        boolean pass=valid && result.exitCode()==0 && (spec.outputContains()==null || result.output().contains(spec.outputContains()));
        String summary=!valid?"检查命令未能完整执行，已保留诊断。":pass?"检查命令通过。":"检查命令未通过。";
        var report=new LinkedHashMap<String,Object>();report.put("version",1);report.put("type","COMMAND");report.put("producerAttempt",input.attemptId());
        report.put("inputSha256",input.sha256());report.put("passed",pass);report.put("valid",valid);report.put("exitCode",result.exitCode());
        report.put("timedOut",result.timedOut());report.put("cancelled",result.cancelled());report.put("outputTruncated",result.outputTruncated());report.put("errorCode",result.error());
        String output=result.output();int end=output.offsetByCodePoints(0,Math.min(10000,output.codePointCount(0,output.length())));
        report.put("output",output.substring(0,end));report.put("reportExcerpt",end<output.length());
        var delivery=new WorkflowDelivery(summary,pass?"PASS":"FAIL",Map.of("summary",new WorkflowDelivery.Value(WorkflowGraph.DataKind.TEXT,json(summary)),
                "report",new WorkflowDelivery.Value(WorkflowGraph.DataKind.JSON,json(report))));
        boolean success=valid && switch(node.completion().kind()) {
            case VERIFIED -> pass;case DELIVERABLES -> true;case OUTCOME -> Objects.equals(node.completion().expectedOutcome(),delivery.outcome());default -> false;
        };
        return new Evaluation(delivery,success);
    }
    private tools.jackson.databind.JsonNode json(Object value){return encoding.decode(encoding.encode(value),tools.jackson.databind.JsonNode.class);}
}
