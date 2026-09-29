package io.opencode.loopper.service.workflow;

import io.opencode.loopper.runtime.*;
import io.opencode.loopper.service.*;
import io.opencode.loopper.persistence.WorkflowModelMapper.Launch;
import io.opencode.loopper.workflow.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.TransactionTemplate;

/** Scoped MCP reads and immutable submission. Acceptance never completes the attempt. */
@Service
public class WorkflowModelTools {
    private final WorkflowRuntimeSupport identity;
    private final WorkflowModelStore store;
    private final WorkflowNodeRuns nodes;
    private final WorkflowCommands commands;
    private final WorkflowWriterCandidates candidates;
    private final WorkflowModelReads reads;
    private final TransactionTemplate transactions;
    private final WorkflowPlanCandidates plans;
    public WorkflowModelTools(WorkflowRuntimeSupport identity, WorkflowModelStore store, WorkflowNodeRuns nodes, WorkflowCommands commands, WorkflowWriterCandidates candidates, WorkflowModelReads reads, TransactionTemplate transactions,WorkflowPlanCandidates plans) {
        this.identity=identity; this.store=store; this.nodes=nodes; this.commands=commands; this.candidates=candidates; this.reads=reads; this.transactions=transactions;this.plans=plans;
    }
    public record Accepted(String attemptId, String sha256, String status) { }
    public record Submission(String attemptId, String requestKey, long expectedAttemptVersion, WorkflowDelivery delivery) { }
    @Transactional(propagation=Propagation.NOT_SUPPORTED)
    public Object call(String tool, Map<String,Object> request) {
        if (request==null || !request.keySet().equals(Set.of("scope","attemptId","args"))
                || !(request.get("args") instanceof Map<?,?> raw)) throw invalid();
        String scope=text(request,"scope",512), attemptId=text(request,"attemptId",100);
        var row=identity.authorize(scope,attemptId,tool);
        if (raw.keySet().stream().anyMatch(key->!(key instanceof String))) throw invalid();
        var args=new LinkedHashMap<String,Object>(); raw.forEach((key,value)->args.put((String)key,value));
        if (Set.of(WorkflowModelProfile.FILES,WorkflowModelProfile.FILE).contains(tool)) { active(row);return reads.file(row,tool,args); }
        return transactions.execute(tx -> call(row,tool,args,scope));
    }
    private Object call(Launch row,String tool,Map<String,Object> args,String scope) {
        if (tool.equals(WorkflowModelProfile.SUBMIT)) return submit(row,args,scope);
        active(row);
        return reads.read(row,tool,args);
    }
    private Accepted submit(Launch row, Map<String,Object> args, String scope) {
        if (!args.keySet().equals(Set.of("requestKey","expectedAttemptVersion","delivery"))) throw invalid();
        String key=text(args,"requestKey",100);
        long version=integer(args,"expectedAttemptVersion",-1,0,Integer.MAX_VALUE);
        String encoded=store.encoding().encode(args.get("delivery"));
        if (encoded.contains(scope)||!encoded.equals(io.opencode.loopper.service.assist.AssistRedaction.text(encoded))) throw new BadRequestException("WORKFLOW_SCOPE_IN_OUTPUT","交付物含临时工具凭证，请移除后提交");
        if (encoded.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>WorkflowDeliveries.limit(store.definition(row).moduleId()))
            throw new BadRequestException("WORKFLOW_DELIVERY_TOO_LARGE","交付内容过大，请缩小本次输出");
        final WorkflowDelivery delivery;
        try { delivery=decodeDelivery(encoded); }
        catch (RuntimeException invalid) { throw invalid(); }
        var submission=new Submission(row.attemptId(),key,version,delivery);
        String digest=store.encoding().digest("NODE_MODEL_SUBMIT",row.attemptId(),submission);
        var replay=commands.replay(key,digest,Accepted.class); if (replay.isPresent()) return replay.get();
        active(row);
        var attempt=store.attempt(row);
        if (attempt.version()!=version) throw WorkflowCommands.conflict();
        if (WorkflowModelProfile.writer(attempt.adapterKey())) {
            var candidate=candidates.submit(row,delivery);
            return commands.record(key,digest,"REQUIREMENT","MODEL_SUBMIT",row.requirementId(),
                    new Accepted(attempt.id(),candidate.sha256(),"PENDING_CAPTURE"));
        }
        var accepted=nodes.accept(attempt,delivery);
        plans.capture(attempt,delivery);
        return commands.record(key,digest,"REQUIREMENT","MODEL_SUBMIT",row.requirementId(),
                new Accepted(attempt.id(),accepted.sha256(),"ACCEPTED"));
    }
    private void active(Launch row) {
        store.activeWork(row);
        if (!Set.of("DISPATCHING","RUNNING").contains(row.state()) || row.suspended() || store.stopProof(row.attemptId()).isPresent())
            throw new ConflictException("WORKFLOW_MODEL_NOT_ACTIVE","该节点已停止或等待恢复，不能继续读取或提交");
    }
    private WorkflowDelivery decodeDelivery(String encoded) {
        var value=store.encoding().decode(encoded,tools.jackson.databind.JsonNode.class);
        if (!value.isObject() || !Set.of("summary","outcome","outputs").containsAll(value.propertyNames())
                || !value.path("summary").isString() || !value.path("outputs").isObject()
                || (value.hasNonNull("outcome") && !value.get("outcome").isString())) throw invalid();
        for (var output:value.get("outputs")) {
            if (!output.isObject() || !output.propertyNames().equals(Set.of("kind","content"))
                    || !output.path("kind").isString() || !output.hasNonNull("content")) throw invalid();
        }
        return store.encoding().decode(encoded,WorkflowDelivery.class);
    }
    static String text(Map<String,Object> args, String name, int max) {
        if (!(args.get(name) instanceof String value) || value.isBlank() || value.length()>max) throw invalid();
        return value;
    }
    static int integer(Map<String,Object> args, String name, int fallback, int min, int max) {
        Object value=args.get(name); if (value==null && !args.containsKey(name)) return fallback;
        if (!(value instanceof Number number) || number.doubleValue()!=number.longValue() || number.longValue()<min || number.longValue()>max) throw invalid();
        return number.intValue();
    }
    static BadRequestException invalid() { return new BadRequestException("WORKFLOW_TOOL_PARAMETERS_INVALID","工具参数不完整或超出范围，请按当前节点工作信息修正"); }
}
