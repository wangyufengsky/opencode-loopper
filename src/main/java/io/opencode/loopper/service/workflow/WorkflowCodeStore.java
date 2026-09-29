package io.opencode.loopper.service.workflow;

import io.opencode.loopper.persistence.*;
import io.opencode.loopper.persistence.WorkflowCodeMapper.*;
import io.opencode.loopper.runtime.DirectWorkspaceLeaseCoordinator.WorkspaceIdentity;
import io.opencode.loopper.runtime.GitProjectScope;
import io.opencode.loopper.runtime.WorkspaceWriterQueue;
import io.opencode.loopper.service.*;
import io.opencode.loopper.workflow.WorkflowCodeSnapshot;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Only immutable identity and manifest writes; Git and content-store I/O remain outside this bean. */
@Service
public class WorkflowCodeStore {
    private final WorkflowCodeMapper code;
    private final WorkflowExecutionMapper execution;
    private final WorkflowPlanMapper plans;
    private final LoopperMapper mapper;
    private final WorkflowNodeRuns nodes;
    private final WorkflowEncoding encoding;
    private final WorkflowWorkspaceMapper workspaces;
    public WorkflowCodeStore(WorkflowCodeMapper code, WorkflowExecutionMapper execution, WorkflowPlanMapper plans,
            LoopperMapper mapper, WorkflowNodeRuns nodes, WorkflowEncoding encoding,WorkflowWorkspaceMapper workspaces) {
        this.code=code; this.execution=execution; this.plans=plans; this.mapper=mapper; this.nodes=nodes; this.encoding=encoding;this.workspaces=workspaces;
    }
    public record Context(WorkflowExecutionRows.Attempt attempt, WorkflowRows.Requirement requirement, ProjectRow project) { }
    public Context context(String attemptId) {
        var attempt=nodes.attempt(attemptId);
        var node=execution.node(attempt.nodeRunId()).orElseThrow(WorkflowCodeStore::invalid);
        var owner=plans.find(node.requirementId()).orElseThrow(WorkflowCodeStore::invalid);
        return new Context(attempt,owner,mapper.findProject(owner.projectId()).orElseThrow(WorkflowCodeStore::invalid));
    }
    @Transactional
    public Snapshot prepare(Context expected, WorkspaceIdentity identity, GitProjectScope scope, String baseline, String result) {
        if(!scope.repository().toString().equals(identity.canonicalRoot()))throw invalid();
        return prepare(expected,identity,scope.prefix(),null,baseline,result);
    }
    public Optional<WorkflowWorkspaceMapper.Workspace> workspace(String id) { return workspaces.find(id); }
    @Transactional
    public Snapshot prepareDirectory(Context expected,WorkspaceIdentity identity,WorkflowWorkspaceMapper.Workspace row,String baseline,String result) {
        if(!row.equals(workspaces.find(expected.attempt().id()).orElseThrow(WorkflowCodeStore::invalid))||row.objectRepository()==null
                ||!Set.of("FROZEN","RESTORING","RESTORED").contains(row.state())||!row.baseTree().equals(baseline)||!row.checkpointTree().equals(result)
                ||!row.canonicalRoot().equals(identity.canonicalRoot())||!row.rootFingerprint().equals(identity.rootFingerprint()))throw invalid();
        return prepare(expected,identity,"",row.objectRepository(),baseline,result);
    }
    private Snapshot prepare(Context expected,WorkspaceIdentity identity,String prefix,String objects,String baseline,String result) {
        var current=context(expected.attempt().id());
        if (current.project().version()!=expected.project().version()
                || !current.project().rootPath().equals(expected.project().rootPath())) throw invalid();
        requireStoppedHolder(current.attempt(),expected.attempt().version(),identity);
        var previous=code.forAttempt(current.attempt().id()).orElse(null);
        if (previous!=null) {
            if (!previous.repository().equals(identity.canonicalRoot()) || !previous.rootFingerprint().equals(identity.rootFingerprint())
                    || !previous.projectPrefix().equals(prefix) || !Objects.equals(previous.objectRepository(),objects)
                    || !previous.baseTree().equals(baseline) || !previous.resultTree().equals(result)) throw invalid();
            return previous;
        }
        if (baseline==null || result==null || !baseline.matches("(?:[0-9a-f]{40}|[0-9a-f]{64})")
                || !result.matches("(?:[0-9a-f]{40}|[0-9a-f]{64})")) throw invalid();
        var row=new Snapshot(UUID.randomUUID().toString(),current.attempt().id(),current.project().id(),current.requirement().id(),
                identity.canonicalRoot(),identity.rootFingerprint(),prefix,baseline,result,current.attempt().inputsSha256(),Instant.now().toString(),objects);
        if (code.insert(row)!=1) throw invalid();
        return row;
    }
    @Transactional
    public WorkflowCodeSnapshot.Reference publish(Snapshot expected, long attemptVersion, WorkspaceIdentity identity,
            WorkflowCodeSnapshot manifest) {
        var row=snapshot(expected.id());
        if (!row.equals(expected) || manifest.version()!=1 || !manifest.snapshotId().equals(row.id())
                || !manifest.attemptId().equals(row.attemptId()) || !manifest.projectId().equals(row.projectId())
                || !manifest.requirementId().equals(row.requirementId()) || !manifest.inputsSha256().equals(row.inputsSha256())
                || !manifest.baseTree().equals(row.baseTree()) || !manifest.resultTree().equals(row.resultTree())
                || !manifest.projectPrefix().equals(row.projectPrefix())) throw invalid();
        requireStoppedHolder(nodes.attempt(row.attemptId()),attemptVersion,identity);
        if (!identity.canonicalRoot().equals(row.repository()) || !identity.rootFingerprint().equals(row.rootFingerprint())) throw invalid();
        String body=encoding.encode(manifest), hash=WorkflowEncoding.hash(body);
        var old=code.manifest(row.id()).orElse(null);
        if (old!=null && (!old.contentJson().equals(body) || !old.sha256().equals(hash))) throw invalid();
        if (old==null && code.publish(new Manifest(row.id(),body,hash,Instant.now().toString()))!=1) throw invalid();
        return new WorkflowCodeSnapshot.Reference(1,row.id(),hash);
    }
    public Snapshot snapshot(String id) { return code.snapshot(id).orElseThrow(()->new NotFoundException("代码快照不存在")); }
    /** Internal stopped-holder inspection before success; never exposed as a downstream input. */
    WorkflowCodeSnapshot stoppedManifest(String attemptId,WorkflowCodeSnapshot.Reference reference) {
        var row=snapshot(reference.snapshotId());var attempt=nodes.attempt(attemptId);var workspace=workspaces.find(attemptId).orElseThrow(WorkflowCodeStore::invalid);
        requireStoppedHolder(attempt,attempt.version(),new WorkspaceIdentity(workspace.canonicalRoot(),workspace.rootFingerprint()));
        if(reference.version()!=1||!row.attemptId().equals(attemptId)||!row.resultTree().equals(workspace.checkpointTree())||!row.inputsSha256().equals(attempt.inputsSha256()))throw invalid();
        var published=code.manifest(row.id()).orElseThrow(WorkflowCodeStore::invalid);
        if(!published.sha256().equals(reference.sha256())||!WorkflowEncoding.hash(published.contentJson()).equals(published.sha256()))throw invalid();
        return encoding.decode(published.contentJson(),WorkflowCodeSnapshot.class);
    }
    public WorkflowCodeSnapshot read(String projectId,String requirementId,String attemptId,WorkflowCodeSnapshot.Reference reference) {
        return read(projectId,requirementId,attemptId,reference,false);
    }
    /** The producer's result panel may inspect a failed, stopped, accepted partial delivery. */
    WorkflowCodeSnapshot readOutput(String projectId,String requirementId,String attemptId,WorkflowCodeSnapshot.Reference reference) {
        return read(projectId,requirementId,attemptId,reference,true);
    }
    private WorkflowCodeSnapshot read(String projectId,String requirementId,String attemptId,WorkflowCodeSnapshot.Reference reference,boolean output) {
        var row=snapshot(reference.snapshotId());
        if (reference.version()!=1 || !row.projectId().equals(projectId) || !row.requirementId().equals(requirementId)
                || !row.attemptId().equals(attemptId)) throw new NotFoundException("当前需求节点中不存在该代码交付");
        String state=nodes.attempt(attemptId).state();
        if (!state.equals("SUCCEEDED") && !(output && state.equals("FAILED") && nodes.hasStop(attemptId)))
            throw new ConflictException("WORK_DELIVERY_NOT_READY","代码节点尚未成功完成，不能作为后续输入；失败成果只能在原节点查看");
        var published=code.manifest(row.id()).orElseThrow(WorkflowCodeStore::invalid);
        if (!published.sha256().equals(reference.sha256()) || !WorkflowEncoding.hash(published.contentJson()).equals(published.sha256())) throw invalid();
        var delivery=encoding.decode(nodes.delivery(attemptId).contentJson(),io.opencode.loopper.workflow.WorkflowDelivery.class);
        boolean accepted=delivery.outputs().values().stream().filter(value->value.kind()==io.opencode.loopper.workflow.WorkflowGraph.DataKind.CODE)
                .anyMatch(value->encoding.encode(value.content()).equals(encoding.encode(reference)));
        if (!accepted) throw invalid();
        return encoding.decode(published.contentJson(),WorkflowCodeSnapshot.class);
    }
    private void requireStoppedHolder(WorkflowExecutionRows.Attempt attempt,long version,WorkspaceIdentity identity) {
        nodes.active(attempt);
        if (attempt.version()!=version || !WorkflowWriterLeases.ADAPTER.equals(attempt.adapterKey())
                || !Set.of("RUNNING","STOPPING").contains(attempt.state()) || execution.stop(attempt.id()).isEmpty()) throw invalid();
        var lease=mapper.findWorkspaceLease(identity.canonicalRoot()).orElseThrow(WorkflowCodeStore::invalid);
        WorkspaceWriterQueue.requireIdentity(lease,identity);
        var queue=mapper.findWorkflowWriter(attempt.id()).orElseThrow(WorkflowCodeStore::invalid);
        if (!attempt.id().equals(lease.holderWorkflowAttemptId()) || lease.state().equals("RELEASED") || !queue.state().equals("ADMITTED")) throw invalid();
    }
    static ConflictException invalid() { return new ConflictException("WORK_CODE_SNAPSHOT_CONFLICT","代码交付身份、停止证明或工作区版本不一致，已保留原记录"); }
}
