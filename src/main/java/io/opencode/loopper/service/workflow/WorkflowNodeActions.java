package io.opencode.loopper.service.workflow;

import static io.opencode.loopper.service.workflow.WorkflowCommands.conflict;
import io.opencode.loopper.domain.*;
import io.opencode.loopper.persistence.*;
import io.opencode.loopper.service.*;
import io.opencode.loopper.workflow.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Manual node admission and human-work adapter. Automated adapters use the same execution ledger. */
@Service
public class WorkflowNodeActions {
    private final WorkflowPlans plans;
    private final WorkflowNodeRuns nodes;
    private final WorkflowNodeInputs inputs;
    private final WorkflowEncoding encoding;
    private final WorkflowCommands commands;
    private final WorkflowSettlement settlement;
    private final WorkflowControlStore controls;
    public WorkflowNodeActions(WorkflowPlans plans, WorkflowNodeRuns nodes, WorkflowNodeInputs inputs,
            WorkflowEncoding encoding, WorkflowCommands commands, WorkflowSettlement settlement, WorkflowControlStore controls) {
        this.plans=plans;this.nodes=nodes;this.inputs=inputs;this.encoding=encoding;this.commands=commands;
        this.settlement=settlement;this.controls=controls;
    }
    public record Start(String requestKey, long expectedVersion, Map<String, WorkflowDelivery.Value> inputs) { }
    public record Complete(String requestKey, long expectedVersion, String attemptId, long expectedAttemptVersion, WorkflowDelivery delivery) { }
    public record Receipt(String id, int revision, long version, String nodeKey, String nodeRunId, long nodeVersion,
                          String attemptId, long attemptVersion, String state) { }
    public record Overview(String id, int revision, long version, String state, List<WorkflowExecutionRows.Summary> nodes) { }
    public record Result(String attemptId, String state, String sha256, WorkflowDelivery delivery) { }
    public record Admission(WorkflowRows.Requirement owner, WorkflowExecutionRows.Node node,
                            WorkflowGraph.Node definition, WorkflowDelivery.Inputs inputs) { }
    public Overview overview(String id) {
        var row = plans.require(id);
        return new Overview(id, row.headRevision(), row.version(), row.state(), nodes.summaries(id, row.headRevision()));
    }
    public WorkflowDelivery.Inputs inputs(String id, String key, String attemptId) {
        plans.require(id); return nodes.inputSnapshot(nodes.scopedAttempt(id, key, attemptId));
    }
    @Transactional(readOnly = true)
    public WorkflowInputPages.Page inputContent(String id, String key, String attemptId, String name, int offset, int limit) {
        plans.require(id);
        var input = nodes.input(nodes.scopedAttempt(id, key, attemptId), name);
        return WorkflowInputPages.page(input, WorkflowInputPages.text(input, encoding), offset, limit);
    }
    public Result result(String id, String key, String attemptId) {
        plans.require(id); var attempt = nodes.scopedAttempt(id, key, attemptId);
        var row = nodes.findDelivery(attempt.id()).orElseThrow(() -> new NotFoundException("本次尝试尚无已接受的交付物"));
        return new Result(attempt.id(), attempt.state(), row.sha256(), encoding.decode(row.contentJson(), WorkflowDelivery.class));
    }

    @Transactional
    public Receipt startHuman(String id, String key, Start request) { return startHuman(id,key,request,null); }
    @Transactional
    public Receipt dispatchHuman(String id,String key,Start request,WorkflowDispatch.Permit permit) { return startHuman(id,key,request,permit); }
    private Receipt startHuman(String id, String key, Start request,WorkflowDispatch.Permit permit) {
        String digest = encoding.digest("NODE_HUMAN_START", id + "/" + key, request);
        var replay = commands.replay(request.requestKey(), digest, Receipt.class); if (replay.isPresent()) return replay.get();
        var admitted = admit(id, key, request,permit);
        requireHuman(admitted.definition());
        var attempt = nodes.begin(admitted.node(), admitted.owner().headRevision(), admitted.inputs(), "human.v1", null);
        attempt = nodes.transition(attempt, WorkflowAttemptState.WAITING_INPUT, LifecycleEvent.REQUIRE_INPUT);
        settlement.humanWaiting(id);
        return acknowledge(request.requestKey(), digest, "HUMAN_START", id, key, attempt.id());
    }

    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public Admission admit(String id, String key, Start request) { return admit(id,key,request,null); }
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public Admission admit(String id, String key, Start request,WorkflowDispatch.Permit permit) {
        var owner = current(id, request.expectedVersion());
        var graph = graph(owner);
        var definition = graph.nodes().stream().filter(node -> node.id().equals(key)).findFirst()
                .orElseThrow(() -> new NotFoundException("流程中不存在此节点"));
        if (nodes.summaries(id, owner.headRevision()).isEmpty()) nodes.initialize(owner, graph); // V142 planning-only history.
        controls.authorize(owner,graph,settlement.progress(owner),key,permit);
        var node = nodes.node(id, owner.headRevision(), key);
        if (node.state().equals("FAILED")) {
            if (!WorkflowAttemptState.valueOf(nodes.attempt(node.latestAttemptId()).state()).terminal()) throw conflict();
        } else if (WorkflowReadiness.decide(graph, key, progress(owner)) != WorkflowReadiness.Decision.READY)
            throw new ConflictException("WORKFLOW_NODE_NOT_READY", "前置节点或条件尚未满足，当前节点不能执行");
        inputs.freezePublic(owner, graph, request.inputs());
        var snapshot = inputs.resolve(owner, definition);
        if (node.latestAttemptId() != null && !sameInputs(nodes.inputs(nodes.attempt(node.latestAttemptId())),snapshot))
            throw new ConflictException("WORKFLOW_RETRY_INPUT_CHANGED", "重试必须沿用原输入，请通过新计划版本更换工作");
        owner=settlement.activate(owner);
        return new Admission(owner, node, definition, snapshot);
    }

    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public void resumeRequirement(String id) {
        var row=plans.require(id);
        if (Set.of("PAUSED","STALLED").contains(row.state())) settlement.activate(row);
    }

    @Transactional
    public Receipt completeHuman(String id, String key, Complete request) {
        String digest = encoding.digest("NODE_HUMAN_COMPLETE", id + "/" + key, request);
        var replay = commands.replay(request.requestKey(), digest, Receipt.class); if (replay.isPresent()) return replay.get();
        var owner = current(id, request.expectedVersion());
        if (owner.state().equals("PENDING_START")) throw conflict();
        var node = nodes.node(id, owner.headRevision(), key);
        var definition = nodes.definition(node); requireHuman(definition);
        if (!Objects.equals(node.latestAttemptId(), request.attemptId())) throw conflict();
        var attempt = nodes.attempt(request.attemptId());
        if (attempt.version() != request.expectedAttemptVersion() || !attempt.state().equals("WAITING_INPUT")) throw conflict();
        if (definition.completion().kind() == WorkflowGraph.CompletionKind.OUTCOME
                && (request.delivery() == null || !Objects.equals(definition.completion().expectedOutcome(), request.delivery().outcome())))
            throw new BadRequestException("WORKFLOW_COMPLETION_UNMET", "人工提交的业务结果尚未满足该节点的完成条件");
        nodes.accept(attempt, request.delivery());
        nodes.stopHuman(attempt);
        nodes.finish(attempt, WorkflowAttemptState.SUCCEEDED);
        settle(id, false, attempt.id());
        return acknowledge(request.requestKey(), digest, "HUMAN_COMPLETE", id, key, attempt.id());
    }

    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public void settle(String id,boolean failed) { settlement.settle(id,failed,null); }
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public void settle(String id,boolean failed,String attempt) { settlement.settle(id,failed,attempt); }
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public void holdDispatch(String id) { settlement.hold(id,"WORKFLOW_NODE_STOP_REQUESTED"); }
    private Map<String, WorkflowReadiness.Progress> progress(WorkflowRows.Requirement owner) {
        var result = new LinkedHashMap<String, WorkflowReadiness.Progress>();
        nodes.summaries(owner.id(), owner.headRevision()).forEach(row -> result.put(row.nodeKey(),
                new WorkflowReadiness.Progress(WorkflowNodeState.valueOf(row.state()), row.outcome())));
        return result;
    }
    private WorkflowRows.Requirement current(String id, long version) {
        var row = plans.require(id);
        if (row.version() != version || !Set.of("PENDING_START", "RUNNING", "PAUSED", "STALLED").contains(row.state())) throw conflict();
        return row;
    }
    private WorkflowGraph graph(WorkflowRows.Requirement row) {
        var revision = plans.revision(row.id(), row.headRevision());
        return encoding.read(revision.definitionJson(), revision.sha256());
    }
    private static boolean sameInputs(WorkflowDelivery.Inputs previous,WorkflowDelivery.Inputs next) {
        return previous.version()==next.version() && previous.requirementId().equals(next.requirementId()) && previous.nodeId().equals(next.nodeId())
                && previous.objective().equals(next.objective()) && previous.values().equals(next.values());
    }
    private static void requireHuman(WorkflowGraph.Node node) {
        if (node.kind() != WorkflowGraph.NodeKind.HUMAN || node.completion().kind() == WorkflowGraph.CompletionKind.VERIFIED
                || node.outputs().stream().anyMatch(output -> Set.of(WorkflowGraph.DataKind.DOCUMENT, WorkflowGraph.DataKind.CODE, WorkflowGraph.DataKind.PLAN).contains(output.kind())))
            throw new BadRequestException("WORKFLOW_HUMAN_ADAPTER_UNSUPPORTED", "此入口仅执行人工文本、结构化结果或检查点；专业模块使用各自执行适配器");
    }
    Receipt acknowledge(String key, String digest, String action, String id, String nodeKey, String attemptId) {
        var owner = plans.require(id); var node = nodes.node(id, owner.headRevision(), nodeKey); var attempt = nodes.attempt(attemptId);
        return commands.record(key, digest, "REQUIREMENT", action, id, new Receipt(id, owner.headRevision(), owner.version(), nodeKey,
                node.id(), node.version(), attempt.id(), attempt.version(), attempt.state()));
    }
}
