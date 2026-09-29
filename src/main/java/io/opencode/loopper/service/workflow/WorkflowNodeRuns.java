package io.opencode.loopper.service.workflow;

import static io.opencode.loopper.service.workflow.WorkflowCommands.conflict;
import io.opencode.loopper.domain.*;
import io.opencode.loopper.lifecycle.LifecycleTransitionService;
import io.opencode.loopper.persistence.*;
import io.opencode.loopper.persistence.WorkflowExecutionRows.*;
import io.opencode.loopper.service.*;
import io.opencode.loopper.workflow.*;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Short-transaction execution ledger. No model, filesystem or workspace operation belongs here. */
@Service
@Transactional(readOnly=true)
public class WorkflowNodeRuns {
    private final WorkflowExecutionMapper mapper;
    private final WorkflowPlanMapper plans;
    private final WorkflowEncoding encoding;
    private final LifecycleTransitionService lifecycle;
    private final WorkflowDeliveryFiles files;
    private final WorkflowWorkContracts workContracts;
    private final WorkflowInputSnapshots snapshots;
    private final WorkflowHistoryReportFormats reportFormats;
    public WorkflowNodeRuns(WorkflowExecutionMapper mapper, WorkflowPlanMapper plans,
            WorkflowEncoding encoding, LifecycleTransitionService lifecycle, WorkflowDeliveryFiles files,
            WorkflowWorkContracts workContracts, WorkflowInputSnapshots snapshots, WorkflowHistoryReportFormats reportFormats) {
        this.mapper=mapper; this.plans=plans; this.encoding=encoding; this.lifecycle=lifecycle;
        this.files=files; this.workContracts=workContracts; this.snapshots=snapshots; this.reportFormats=reportFormats;
    }
    @Transactional(propagation = Propagation.MANDATORY)
    public void initialize(WorkflowRows.Requirement requirement, WorkflowGraph graph) {
        var existing = mapper.summaries(requirement.id(), requirement.headRevision());
        if (!existing.isEmpty()) throw conflict();
        for (var definition : graph.nodes()) {
            String body = encoding.encode(definition), now = Instant.now().toString(), id = UUID.randomUUID().toString();
            var row = new Node(id, requirement.id(), definition.id(), body, WorkflowEncoding.hash(body),
                    WorkflowNodeState.PENDING.name(), 0, null, 0, now, now);
            lifecycle.create(subject(row), row.state(), Map.of("planRevision", requirement.headRevision(), "nodeKey", row.nodeKey()),
                    () -> mapper.insertNode(row), WorkflowCommands::conflict);
            reportFormats.freeze(row,definition,null);
            if (mapper.bind(requirement.id(), requirement.headRevision(), definition.id(), row.id()) != 1) throw conflict();
        }
    }
    @Transactional(propagation=Propagation.MANDATORY)
    public void rebind(WorkflowRows.Requirement previous,WorkflowRows.Requirement next,WorkflowGraph graph,WorkflowPlanChanges.Changes changes) {
        var old=new HashMap<String,Node>();mapper.planNodes(previous.id(),previous.headRevision()).forEach(node->old.put(node.nodeKey(),node));
        var counts=new HashMap<String,Integer>();mapper.attemptCounts(previous.id()).forEach(row->counts.put(row.nodeKey(),row.attempts()));
        for(var existing:old.values())if(changes.affected().contains(existing.nodeKey()) && existing.latestAttemptId()!=null) {
            var attempt=attempt(existing.latestAttemptId());
            if(!WorkflowAttemptState.valueOf(attempt.state()).terminal() || mapper.stop(attempt.id()).isEmpty())
                throw new ConflictException("WORKFLOW_STOP_UNCONFIRMED","受影响节点尚未证明停止，请先处理原尝试。");
        }
        for(var definition:graph.nodes()) {
            var row=old.get(definition.id());
            if(row==null || changes.affected().contains(definition.id())) {
                String now=Instant.now().toString(),body=encoding.encode(definition);
                row=new Node(UUID.randomUUID().toString(),next.id(),definition.id(),body,WorkflowEncoding.hash(body),"PENDING",counts.getOrDefault(definition.id(),0),null,0,now,now);
                var fresh=row;
                lifecycle.create(subject(fresh),fresh.state(),Map.of("planRevision",next.headRevision(),"nodeKey",fresh.nodeKey(),"previousAttempts",fresh.attemptCount()),
                        ()->mapper.insertNode(fresh),WorkflowCommands::conflict);
                reportFormats.freeze(fresh,definition,old.get(definition.id()));
            }
            if(mapper.bind(next.id(),next.headRevision(),definition.id(),row.id())!=1)throw conflict();
        }
    }
    public List<Summary> summaries(String requirement, int revision) { return mapper.summaries(requirement, revision); }
    public Node node(String requirement, int revision, String key) {
        return mapper.nodeInPlan(requirement, revision, key).orElseThrow(() -> new NotFoundException("该计划中不存在此节点"));
    }
    public Attempt attempt(String id) {
        return mapper.attempt(id).orElseThrow(() -> new NotFoundException("节点执行尝试不存在"));
    }
    public Attempt scopedAttempt(String requirementId, String nodeKey, String attemptId) {
        var row = attempt(attemptId); var node = requireNode(row.nodeRunId());
        if (!node.requirementId().equals(requirementId) || !node.nodeKey().equals(nodeKey))
            throw new NotFoundException("该需求节点中不存在此执行尝试");
        return row;
    }
    public WorkflowGraph.Node definition(Node row) {
        if (!WorkflowEncoding.hash(row.definitionJson()).equals(row.definitionSha256())) throw corrupt();
        return encoding.decode(row.definitionJson(), WorkflowGraph.Node.class);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Attempt begin(Node expected, int planRevision, WorkflowDelivery.Inputs inputs, String adapter, String roleSnapshot) {
        var row = current(expected, planRevision);
        if (!plans.find(row.requirementId()).orElseThrow().state().equals("RUNNING")) throw conflict();
        if (!Set.of("PENDING", "FAILED").contains(row.state())) throw conflict();
        if (row.latestAttemptId() != null) {
            var previous = attempt(row.latestAttemptId());
            if (!WorkflowAttemptState.valueOf(previous.state()).terminal() || mapper.stop(previous.id()).isEmpty()) throw conflict();
        }
        if (!inputs.requirementId().equals(row.requirementId()) || inputs.planRevision() != planRevision
                || !inputs.nodeId().equals(row.nodeKey())) throw corrupt();
        var definition = definition(row);
        workContracts.validate(definition,inputs);
        boolean human = definition.kind() == WorkflowGraph.NodeKind.HUMAN;
        boolean system=definition.kind()==WorkflowGraph.NodeKind.SYSTEM;
        if(human!= "human.v1".equals(adapter) || system!=Set.of(WorkflowVerification.ADAPTER,WorkflowCommandVerification.ADAPTER,WorkflowReviewContract.ADAPTER,WorkflowSourceSnapshot.ADAPTER,WorkflowDocument.ADAPTER,WorkflowDocument.ASSESSMENT_ADAPTER,WorkflowHistoryReport.ADAPTER,WorkflowSnapshotReport.ADAPTER,WorkflowSourcePlan.ADAPTER,WorkflowSourcePlan.TEST_ADAPTER,WorkflowSourcePlan.DOCUMENT_ADAPTER,WorkflowHistoryReport.PLAN_ADAPTER,WorkflowSnapshotReport.PLAN_ADAPTER,WorkflowTestSummary.ADAPTER,WorkflowTestProfile.ADAPTER).contains(adapter)
                || !human && !system && (roleSnapshot==null || roleSnapshot.isBlank()) || system && roleSnapshot!=null)throw corrupt();
        String body = encoding.encode(snapshots.freeze(inputs));
        if (body.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 2 * 1024 * 1024)
            throw new BadRequestException("WORKFLOW_INPUT_TOO_LARGE", "节点输入超过 2 MiB，请使用受管文件或分段资料引用");
        String now = Instant.now().toString();
        var attempt = new Attempt(UUID.randomUUID().toString(), row.id(), row.attemptCount() + 1, planRevision,
                WorkflowAttemptState.PREPARING.name(), body, WorkflowEncoding.hash(body), roleSnapshot,
                adapter, null, 0, now, now);
        lifecycle.create(subject(attempt, row), attempt.state(), Map.of("nodeId", row.id(), "ordinal", attempt.ordinal()),
                () -> mapper.insertAttempt(attempt), WorkflowCommands::conflict);
        lifecycle.transition(subject(row), row.state(), WorkflowNodeState.ACTIVE.name(),
                row.state().equals("FAILED") ? LifecycleEvent.RETRY : LifecycleEvent.START, null, Map.of("attemptId", attempt.id()),
                () -> mapper.activate(row.id(), row.version(), row.state(), attempt.id(), now), WorkflowCommands::conflict);
        return attempt;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Attempt transition(Attempt expected, WorkflowAttemptState next, LifecycleEvent event) {
        var row = active(expected);
        var node = requireNode(row.nodeRunId());
        lifecycle.transition(subject(row, node), row.state(), next.name(), event, null, Map.of("nodeId", node.id()),
                () -> mapper.transitionAttempt(row.id(), row.version(), row.state(), next.name(), Instant.now().toString()), WorkflowCommands::conflict);
        return attempt(row.id());
    }

    /** Candidate acceptance is durable but does not complete either lifecycle. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Delivery accept(Attempt expected, WorkflowDelivery delivery) {
        var row = active(expected);
        if (!Set.of("RUNNING", "WAITING_INPUT").contains(row.state())) throw conflict();
        if (plans.find(requireNode(row.nodeRunId()).requirementId()).orElseThrow().state().equals("STOPPING")) throw conflict();
        var definition = definition(requireNode(row.nodeRunId()));
        WorkflowDeliveries.validate(definition, delivery);
        delivery=workContracts.accept(row,definition,inputs(row),delivery);
        delivery.outputs().values().forEach(value -> files.require(row, value));
        String body = encoding.encode(delivery), hash = WorkflowEncoding.hash(body);
        if (body.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > WorkflowDeliveries.limit(definition.moduleId()))
            throw new BadRequestException("WORKFLOW_DELIVERY_TOO_LARGE", "节点交付超过对应模块的正文上限，请精简内容或使用受管文件清单");
        var previous = mapper.delivery(row.id());
        if (previous.isPresent()) {
            if (!previous.get().contentJson().equals(body) || !previous.get().sha256().equals(hash)) throw conflict();
            return previous.get();
        }
        var result = new Delivery(row.id(), body, hash, delivery.outcome(), Instant.now().toString());
        if (mapper.insertDelivery(result) != 1) throw conflict();
        return result;
    }

    /** Only the human adapter can prove absence of external work without observing a remote session. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void stopHuman(Attempt expected) {
        var row = active(expected);
        if (!row.adapterKey().equals("human.v1") || row.externalSessionId() != null
                || definition(requireNode(row.nodeRunId())).kind() != WorkflowGraph.NodeKind.HUMAN) throw corrupt();
        if (mapper.stop(row.id()).isEmpty() && mapper.insertStop(new Stop(row.id(), "NO_EXTERNAL_WORK", null,
                encoding.encode(Map.of("adapter", "human.v1")), Instant.now().toString())) != 1) throw conflict();
    }
    @Transactional(propagation=Propagation.MANDATORY)
    public void stopVerification(Attempt expected) {
        var row=active(expected);var definition=definition(requireNode(row.nodeRunId()));
        if(!row.adapterKey().equals(WorkflowVerification.ADAPTER) || row.externalSessionId()!=null || row.roleSnapshotJson()!=null
                || definition.kind()!=WorkflowGraph.NodeKind.SYSTEM || !WorkflowVerification.MODULE.equals(definition.moduleId())
                || definition.moduleVersion()!=1)throw corrupt();
        if(mapper.stop(row.id()).isEmpty() && mapper.insertStop(new Stop(row.id(),"NO_EXTERNAL_WORK",null,
                encoding.encode(Map.of("adapter",WorkflowVerification.ADAPTER,"mode","FIXED_BYTES_ONLY")),Instant.now().toString()))!=1)throw conflict();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void finish(Attempt expected, WorkflowAttemptState outcome) {
        if (!outcome.terminal()) throw new IllegalArgumentException("Attempt outcome must be terminal");
        var row = active(expected); var node = requireNode(row.nodeRunId());
        if (mapper.stop(row.id()).isEmpty()) throw new ConflictException("WORKFLOW_STOP_UNCONFIRMED", "尚未确认执行停止，不能完成或创建下一次尝试");
        if (outcome == WorkflowAttemptState.SUCCEEDED && mapper.delivery(row.id()).isEmpty()) throw corrupt();
        var event = outcome == WorkflowAttemptState.SUCCEEDED ? LifecycleEvent.COMPLETE
                : outcome == WorkflowAttemptState.FAILED ? LifecycleEvent.FAIL : LifecycleEvent.ABORT;
        transition(row, outcome, event);
        var next = WorkflowNodeState.valueOf(outcome.name());
        lifecycle.transition(subject(node), node.state(), next.name(), event, null, Map.of("attemptId", row.id()),
                () -> mapper.transitionNode(node.id(), node.version(), node.state(), next.name(), Instant.now().toString()), WorkflowCommands::conflict);
    }

    @Transactional(propagation=Propagation.MANDATORY)
    public void stopReviewGate(Attempt expected) {
        var row=active(expected);var definition=definition(requireNode(row.nodeRunId()));
        if(!row.adapterKey().equals(WorkflowReviewContract.ADAPTER) || row.externalSessionId()!=null || row.roleSnapshotJson()!=null)throw corrupt();
        WorkflowReviewContract.requireGate(definition);
        if(mapper.stop(row.id()).isEmpty() && mapper.insertStop(new Stop(row.id(),"NO_EXTERNAL_WORK",null,
                encoding.encode(Map.of("adapter",WorkflowReviewContract.ADAPTER,"mode","FIXED_REVIEW_EVIDENCE")),Instant.now().toString()))!=1)throw conflict();
    }
    public boolean hasStop(String id){return mapper.stop(id).isPresent();}

    @Transactional(propagation=Propagation.MANDATORY)
    public void stopDocument(Attempt expected) {
        var row=active(expected);
        if(!row.adapterKey().equals(WorkflowDocument.adapter(definition(requireNode(row.nodeRunId()))))||row.externalSessionId()!=null||row.roleSnapshotJson()!=null)throw corrupt();
        WorkflowDocument.require(definition(requireNode(row.nodeRunId())));
        if(mapper.stop(row.id()).isEmpty()&&mapper.insertStop(new Stop(row.id(),"NO_EXTERNAL_WORK",null,
                encoding.encode(Map.of("adapter",row.adapterKey(),"mode","DATABASE_DOCUMENT_RENDERING")),Instant.now().toString()))!=1)throw conflict();
    }

    @Transactional(propagation=Propagation.MANDATORY)
    public void stopSourcePlan(Attempt expected) {
        var row=active(expected);
        if(!row.adapterKey().equals(WorkflowSourcePlan.adapter(definition(requireNode(row.nodeRunId()))))||row.externalSessionId()!=null||row.roleSnapshotJson()!=null)throw corrupt();
        if(mapper.stop(row.id()).isEmpty()&&mapper.insertStop(new Stop(row.id(),"NO_EXTERNAL_WORK",null,
                encoding.encode(Map.of("adapter",row.adapterKey(),"mode","DETERMINISTIC_PLAN_CANDIDATE")),Instant.now().toString()))!=1)throw conflict();
    }

    @Transactional(propagation=Propagation.MANDATORY)
    public void stopTestSummary(Attempt expected) {
        var row=active(expected);
        if(!row.adapterKey().equals(WorkflowTestSummary.ADAPTER)||row.externalSessionId()!=null||row.roleSnapshotJson()!=null)throw corrupt();
        WorkflowTestSummary.require(definition(requireNode(row.nodeRunId())));
        if(mapper.stop(row.id()).isEmpty()&&mapper.insertStop(new Stop(row.id(),"NO_EXTERNAL_WORK",null,
                encoding.encode(Map.of("adapter",row.adapterKey(),"mode","FIXED_TEST_SUMMARY")),Instant.now().toString()))!=1)throw conflict();
    }

    @Transactional(propagation=Propagation.MANDATORY)
    public void stopTestProfile(Attempt expected) {
        var row=active(expected);
        if(!row.adapterKey().equals(WorkflowTestProfile.ADAPTER)||row.externalSessionId()!=null||row.roleSnapshotJson()!=null)throw corrupt();
        WorkflowTestProfile.require(definition(requireNode(row.nodeRunId())));
        if(mapper.stop(row.id()).isEmpty()&&mapper.insertStop(new Stop(row.id(),"NO_EXTERNAL_WORK",null,
                encoding.encode(Map.of("adapter",WorkflowTestProfile.ADAPTER,"mode","FROZEN_TEST_CONFIGURATION")),Instant.now().toString()))!=1)throw conflict();
    }

    @Transactional(propagation=Propagation.MANDATORY)
    public void stopSource(Attempt expected) {
        var row=active(expected);
        if(!row.adapterKey().equals(WorkflowSourceSnapshot.ADAPTER)||row.externalSessionId()!=null||row.roleSnapshotJson()!=null)throw corrupt();
        WorkflowSourceSnapshot.require(definition(requireNode(row.nodeRunId())));
        if(mapper.stop(row.id()).isEmpty()&&mapper.insertStop(new Stop(row.id(),"NO_EXTERNAL_WORK",null,
                encoding.encode(Map.of("adapter",WorkflowSourceSnapshot.ADAPTER,"mode","READ_ONLY_CAPTURE_PRIVATE_BYTES")),Instant.now().toString()))!=1)throw conflict();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void skip(Node expected, int revision) {
        var row = current(expected, revision);
        lifecycle.transition(subject(row), row.state(), WorkflowNodeState.SKIPPED.name(), LifecycleEvent.SKIP, null, Map.of(),
                () -> mapper.transitionNode(row.id(), row.version(), row.state(), WorkflowNodeState.SKIPPED.name(), Instant.now().toString()), WorkflowCommands::conflict);
    }
    public Delivery delivery(String attemptId) {
        return findDelivery(attemptId).orElseThrow(WorkflowNodeRuns::corrupt);
    }
    public Optional<Delivery> findDelivery(String attemptId) {
        var row = mapper.delivery(attemptId);
        if (row.isPresent() && !WorkflowEncoding.hash(row.get().contentJson()).equals(row.get().sha256())) throw corrupt();
        return row;
    }
    public WorkflowDelivery.Inputs inputSnapshot(Attempt row) { return snapshots.snapshot(row); }
    public WorkflowDelivery.Inputs inputs(Attempt row) { return snapshots.materialize(inputSnapshot(row)); }
    public WorkflowDelivery.Input input(Attempt row, String name) { return snapshots.input(inputSnapshot(row), name); }
    public Attempt active(Attempt expected) {
        var row = attempt(expected.id()); var node = requireNode(row.nodeRunId());
        // An approved edit may carry this unchanged running node into a newer plan.
        current(node, plans.find(node.requirementId()).orElseThrow(WorkflowNodeRuns::corrupt).headRevision());
        if (row.version() != expected.version() || !Objects.equals(node.latestAttemptId(), row.id()) || !node.state().equals("ACTIVE")) throw conflict();
        return row;
    }
    private Node current(Node expected, int revision) {
        var row = requireNode(expected.id());
        var owner = plans.find(row.requirementId()).orElseThrow(WorkflowNodeRuns::corrupt);
        if (owner.headRevision() != revision || row.version() != expected.version()
                || !Set.of("RUNNING", "PAUSED", "STALLED", "STOPPING").contains(owner.state())
                || !node(owner.id(), revision, row.nodeKey()).id().equals(row.id())) throw conflict();
        return row;
    }
    public Node requireNode(String id) { return mapper.node(id).orElseThrow(WorkflowNodeRuns::corrupt); }
    private LifecycleTransitionService.Subject subject(Node row) {
        var owner = plans.find(row.requirementId()).orElseThrow(WorkflowNodeRuns::corrupt);
        return new LifecycleTransitionService.Subject(LifecycleMachineType.WORKFLOW_NODE, row.id(), LifecycleScopeType.PROJECT, owner.projectId());
    }
    private LifecycleTransitionService.Subject subject(Attempt row, Node node) {
        var owner = plans.find(node.requirementId()).orElseThrow(WorkflowNodeRuns::corrupt);
        return new LifecycleTransitionService.Subject(LifecycleMachineType.WORKFLOW_ATTEMPT, row.id(), LifecycleScopeType.PROJECT, owner.projectId());
    }
    private static ConflictException corrupt() { return new ConflictException("WORKFLOW_EXECUTION_INCONSISTENT", "节点执行快照或交付物不一致，已保留原记录"); }
}
